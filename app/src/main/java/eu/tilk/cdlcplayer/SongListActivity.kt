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

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.launch
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SongListActivity: AppCompatActivity() {
    private val songListViewModel : SongListViewModel by viewModels()

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerview)
        val emptyView = findViewById<TextView>(R.id.empty_view)
        emptyView.movementMethod = LinkMovementMethod.getInstance()

        val adapter = SongListAdapter(this,
            playCallback = { _, arrangement ->
                val intent = Intent(this, ViewerActivity::class.java).apply {
                    putExtra(ViewerActivity.SONG_ID, arrangement.persistentID)
                }
                startActivity(intent)
            },
            deleteCallback = { song ->
                songListViewModel.deleteSong(song) {
                    if (it != null) {
                        Log.d("song_fail", it.stackTraceToString())
                        AlertDialog.Builder(this).apply {
                            setTitle(getString(R.string.deleting_song_failed))
                            setMessage(getString(R.string.could_not_delete_song))
                            create().show()
                        }
                    }
                }
            }
        )

        emptyView.text = getString(R.string.loading_song_list)
        fun emptyViewVisible(b : Boolean) {
            if (b) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
        recyclerView.adapter = adapter
        songListViewModel.list.observe(this) { songs ->
            songs?.let {
                adapter.setSongs(it)
                emptyViewVisible(it.isEmpty())
                emptyView.setHtml(getString(R.string.no_songs))
            }
        }
        handleIntent(intent)
    }

    override fun onCreateOptionsMenu(menu : Menu) : Boolean {
        menuInflater.inflate(R.menu.song_list_menu, menu)
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val item = menu.findItem(R.id.app_bar_search)
        val actionView = item.actionView as SearchView
        actionView.setSearchableInfo(searchManager.getSearchableInfo(componentName))
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(p0 : MenuItem) : Boolean {
                handleSearch(actionView.query.toString())
                return true
            }

            override fun onMenuItemActionCollapse(p0 : MenuItem) : Boolean {
                handleSearch("")
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item : MenuItem) : Boolean {
        when (item.itemId) {
            R.id.add_song -> {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "*/*"
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                startActivityForResult(intent, READ_REQUEST_CODE)
            }
            R.id.scan_dir -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, DIRECTORY_SCAN_CODE)
            }
            R.id.settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.about -> {
                val intent = Intent(this, AboutActivity::class.java)
                startActivity(intent)
            }
            R.id.sortByAlbumName ->
                songListViewModel.sortOrder = SongListViewModel.SortOrder.ALBUM_NAME
            R.id.sortByAlbumYear ->
                songListViewModel.sortOrder = SongListViewModel.SortOrder.ALBUM_YEAR
            R.id.sortByArtist ->
                songListViewModel.sortOrder = SongListViewModel.SortOrder.ARTIST
            R.id.sortByTitle ->
                songListViewModel.sortOrder = SongListViewModel.SortOrder.TITLE
            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun String.truncate(maxLength: Int = 30): String {
        if (this.length <= maxLength) return this
        val startLength = (maxLength - 3) / 2
        val endLength = maxLength - 8 - startLength
        return "${this.take(startLength)}...${this.takeLast(endLength)}"
    }

    @Deprecated("Deprecated in Java")
    @OptIn(ExperimentalUnsignedTypes::class)
    override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {
        if ((requestCode == READ_REQUEST_CODE || requestCode == DIRECTORY_SCAN_CODE) && resultCode == Activity.RESULT_OK && data != null) {
            val progressLayout = findViewById<LinearLayout>(R.id.progressLayout)
            val progressBar = findViewById<LinearProgressIndicator>(R.id.progressBar)
            val progressText = findViewById<TextView>(R.id.progressText)



            songListViewModel.songAddProgress.observeAndCall(this) {
                progressBar.isIndeterminate = false
                progressBar.progress = it.roundToInt()
            }

            songListViewModel.songAddProgress.value = 0.0

            val url = data.data

            if (url != null) {
                val psarcs = ArrayList<Uri>()
                if (requestCode == DIRECTORY_SCAN_CODE) {
                    val rootDir = DocumentFile.fromTreeUri(this, url)
                    fun findPsarcFiles(directory: DocumentFile) {
                        for (file in directory.listFiles()) {
                            if (file.isDirectory) {
                                // If it's a directory, scan inside it
                                findPsarcFiles(file)
                            } else if (file.isFile && file.name?.endsWith(".psarc") == true) {
                                // If it's a .psarc file, add its URI to the list
                                psarcs.add(file.uri)
                            }
                        }
                    }

                    if (rootDir != null) {
                        findPsarcFiles(rootDir)
                    }
                } else {
                    psarcs.add(url)
                }

                lifecycleScope.launch {
                    progressLayout.visibility = View.VISIBLE
                    progressText.text = "Preparing import..."
                    var i = 1
                    for (psarc in psarcs) {
                        progressBar.progress = 0
                        progressBar.isIndeterminate = true
                        progressText.text = "Importing ${psarc.lastPathSegment?.truncate()}... ($i/${psarcs.size})"

                        val error = songListViewModel.decodeAndInsert(psarc)
                        if (error != null) {
                            Log.d("song_fail", error.stackTraceToString())
                            AlertDialog.Builder(this@SongListActivity).apply {
                                setTitle(R.string.error_loading_song_title)
                                setMessage(R.string.error_loading_song_message)
                                create().show()
                            }
                        }
                        i += 1
                    }
                    progressLayout.visibility = View.GONE
                    Toast.makeText(this@SongListActivity, "Success", Toast.LENGTH_SHORT).show()
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent : Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent : Intent) {
        when (intent.action) {
            Intent.ACTION_SEARCH ->
                handleSearch(intent.getStringExtra(SearchManager.QUERY) ?: "")
        }
    }

    private fun handleSearch(query : String) {
        songListViewModel.search = query
    }

    companion object {
        private const val READ_REQUEST_CODE = 42
        private const val DIRECTORY_SCAN_CODE = 43
    }
}