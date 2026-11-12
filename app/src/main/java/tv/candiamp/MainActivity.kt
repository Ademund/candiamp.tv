package tv.candiamp

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tv.candiamp.data.Credentials
import tv.candiamp.data.LastFmCredentials
import tv.candiamp.data.MusicLibrary
import tv.candiamp.data.RadioLibrary
import tv.candiamp.data.Scrobbler
import tv.candiamp.playback.Playback
import tv.candiamp.ui.App
import tv.candiamp.ui.CandiampTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credentials = Credentials(this)
        val library = MusicLibrary(this, credentials)
        val radio = RadioLibrary(this)
        val lastFm = LastFmCredentials(this)
        val scrobbler = Scrobbler.get(this, lastFm)
        Playback.player(this)
        Playback.addListener(scrobbler)
        scrobbler.start()
        setContent {
            CandiampTheme { App(credentials, library, radio, lastFm, scrobbler) }
        }
    }

    /** The transport keys of a TV remote reach the player wherever the viewer is. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Playback.toggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Playback.next()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Playback.previous()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
