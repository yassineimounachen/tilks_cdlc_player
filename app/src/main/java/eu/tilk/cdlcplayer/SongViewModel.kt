/*
 *     Copyright (C) 2021  Marek Materzok
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import eu.tilk.cdlcplayer.song.Song2014
import eu.tilk.cdlcplayer.song.Vocal
import eu.tilk.cdlcplayer.viewer.RepeaterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

class SongViewModel(private val app : Application) : AndroidViewModel(app) {
    val song = MutableLiveData<Song2014>()
    val paused = MutableLiveData(false)
    val speed = MutableLiveData(1f)
    val repeater = MutableLiveData<RepeaterInfo>()
    val currentWord = MutableLiveData(-1)
    val sentenceStart = MutableLiveData(0)

    fun loadSong(songId : String) = viewModelScope.launch(Dispatchers.IO) {
        val loadedSong : Song2014 = XmlMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(app.openFileInput("$songId.xml"))

        var lyrics : List<Vocal>
        try {
            lyrics = XmlMapper()
                .registerKotlinModule()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(app.openFileInput("${loadedSong.songKey}.lyrics.xml"))
        } catch (fnfe : FileNotFoundException) {
            lyrics = emptyList()
        }

        loadedSong.vocals = lyrics
        withContext(Dispatchers.Main) {
            song.value = loadedSong
        }
    }
}