/*
 *     Copyright (C) 2020  Marek Materzok
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.tilk.cdlcplayer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import eu.tilk.cdlcplayer.data.*
import eu.tilk.cdlcplayer.psarc.PSARCReader
import eu.tilk.cdlcplayer.song.Song2014
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class SongListViewModel(private val app : Application) : AndroidViewModel(app) {
    private val database = SongRoomDatabase.getDatabase(app)
    private val songDao : SongDao = SongRoomDatabase.getDatabase(app).songDao()
    private val arrangementDao : ArrangementDao = SongRoomDatabase.getDatabase(app).arrangementDao()
    val songAddProgress = MutableLiveData(0)

    private fun exceptionHandler(handler : (Throwable) -> Unit) =
        CoroutineExceptionHandler{_ , throwable ->
            viewModelScope.launch(Dispatchers.Main) {
                handler(throwable)
            }
        }

    fun deleteSong(song: SongWithArrangements, handler : (Throwable?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                actualDeleteSong(song)
                withContext(Dispatchers.Main) { handler(null) }
            } catch (throwable : Throwable) {
                withContext(Dispatchers.Main) { handler(throwable) }
            }
        }
    }

    private suspend fun actualDeleteSong(song: SongWithArrangements) {
        database.withTransaction {
            for (arrangement in song.arrangements) {
                app.deleteFile("${arrangement.persistentID}.xml")
                arrangementDao.deleteArrangement(arrangement.persistentID)
            }
            app.deleteFile("${song.song.key}.lyrics.xml")
            app.deleteFile("${song.song.key}.opus")
            songDao.deleteSong(song.song.key)
        }
    }

    @ExperimentalUnsignedTypes
    fun decodeAndInsert(uri : Uri, handler : (Throwable?) -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputFile = File(app.cacheDir, UUID.randomUUID().toString() + "output.CoroutineExceptionHandler {psarc")
                app.contentResolver.openInputStream(uri).use { input ->
                    if (input != null) FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                FileInputStream(outputFile).use { stream ->
                    outputFile.delete()
                    val psarc = PSARCReader(stream)
                    val songs = ArrayList<Song2014>()
                    for (f in psarc.listFiles("""manifests/.*\.json""".toRegex())) {
                        val baseNameMatch =
                            """manifests/.*/([^/]*)\.json""".toRegex().matchEntire(f)
                        val baseName = baseNameMatch!!.groupValues[1]
                        val manifest = psarc.inflateManifest(f)
                        val attributes = manifest.entries.values.first().values.first()
                        when (attributes.arrangementName) {
                            "Lead", "Combo", "Rhythm", "Bass", "Vocals", "JVocals" ->
                                songs.add(
                                    psarc.inflateSng(
                                        "songs/bin/generic/$baseName.sng",
                                        attributes
                                    )
                                )
                        }
                    }

                    songAddProgress.postValue(10)

                    val groupedSongs = songs.groupBy { song2014 -> song2014.songKey }

                    groupedSongs.keys.forEachIndexed { i, songKey ->
                        makeWem(songKey, psarc)
                        songAddProgress.postValue(10 + (((i+1)*90.0)/groupedSongs.keys.size).roundToInt())
                    }

                    insert(groupedSongs)
                }
                withContext(Dispatchers.Main) { handler(null) }
            } catch (throwable : Throwable) {
                withContext(Dispatchers.Main) { handler(throwable) }
            }
        }

    private fun makeWem(songKey : String, psarc : PSARCReader) {
        val bnk = File(app.cacheDir, "$songKey.bnk")
        val wem = File(app.cacheDir, "$songKey.wem")
        val wav = File(app.cacheDir, "$songKey.wav")
        val opus = File(app.filesDir, "$songKey.opus")

        val bnkBA = psarc.inflateFile("audio/windows/song_${songKey.lowercase()}.bnk")

        bnk.writeBytes(bnkBA)
        val where = File(app.applicationInfo.nativeLibraryDir)
        val bnkInfo =
            ProcessBuilder("./libvgmstream.so", "-m", bnk.absolutePath)
                .directory(where)
                .start()
        bnkInfo.waitFor()
        val bnkInfoText =
            bnkInfo.inputStream.readBytes().toString(Charsets.UTF_8)
        bnkInfo.destroy()
        bnk.delete()

        val streamName = """stream name: ([0-9]+)""".toRegex().find(bnkInfoText)!!.groupValues[1]
        val wemBA = psarc.inflateFile("audio/windows/$streamName.wem")

        wem.writeBytes(wemBA)

        val wem2wav = ProcessBuilder(
            "./libvgmstream.so",
            "-o",
            wav.absolutePath,
            wem.absolutePath
        )
            .directory(where)
            .start()
        wem2wav.waitFor()
        wem2wav.destroy()
        wem.delete()

        val wav2opus = ProcessBuilder(
            "./libopusenc.so",
            "--comp",
            "0",
            wav.absolutePath,
            opus.absolutePath
        )
            .directory(where)
            .start()
        wav2opus.waitFor()
        wav2opus.destroy()
        wav.delete()
    }

    private suspend fun insert(songs : Map<String, List<Song2014>>) {
        for (song in songs.values.flatten()) {
            if (song.vocals.isNotEmpty()) {
                app.openFileOutput("${song.songKey}.lyrics.xml", Context.MODE_PRIVATE).use {
                    it.write(
                        XmlMapper().registerModule(KotlinModule())
                            .writeValueAsBytes(song.vocals)
                    )
                }
            } else {
                app.openFileOutput("${song.persistentID}.xml", Context.MODE_PRIVATE).use {
                    it.write(
                        XmlMapper().registerModule(KotlinModule())
                            .writeValueAsBytes(song)
                    )
                }
            }
        }
        database.withTransaction {
            for (song in songs.values.flatten()) {
                if (song.vocals.isEmpty()) arrangementDao.insert(Arrangement(song))
            }

            for (songKey in songs.keys) {
                songDao.insert(Song(songs[songKey]!!.first{ song -> song.vocals.isEmpty() }))
            }
        }
    }

    enum class SortOrder {
        TITLE, ARTIST, ALBUM_NAME, ALBUM_YEAR
    }

    private var currentList = songDao.getSongsByTitle()
        set(value) {
            listMediator.removeSource(currentList)
            field = value
            listMediator.addSource(value) { listMediator.value = it }
        }
    private val listMediator = MediatorLiveData<List<SongWithArrangements>>().apply {
        addSource(currentList) { value = it }
    }

    val list : LiveData<List<SongWithArrangements>> get() = listMediator

    var sortOrder : SortOrder = SortOrder.TITLE
        set(value) {
            field = value
            updateList()
        }

    var search : String = ""
        set(value) {
            field = value
            updateList()
        }

    private fun updateList() {
        currentList = if (search == "")
            when (sortOrder) {
                SortOrder.TITLE -> songDao.getSongsByTitle()
                SortOrder.ARTIST -> songDao.getSongsByArtist()
                SortOrder.ALBUM_NAME -> songDao.getSongsByAlbumName()
                SortOrder.ALBUM_YEAR -> songDao.getSongsByAlbumYear()
            }
        else
            when (sortOrder) {
                SortOrder.TITLE -> songDao.getSongsByTitleSearch("%$search%")
                SortOrder.ARTIST -> songDao.getSongsByArtistSearch("%$search%")
                SortOrder.ALBUM_NAME -> songDao.getSongsByAlbumNameSearch("%$search%")
                SortOrder.ALBUM_YEAR -> songDao.getSongsByAlbumYearSearch("%$search%")
            }
    }
}