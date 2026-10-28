package `is`.xyz.mpv.cine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import `is`.xyz.mpv.MPVActivity

/** Plays a stream URL in the mpv activity, resuming where the user stopped. */
object CinePlayer {

    fun play(
        context: Context,
        title: CineTitle,
        key: String,
        displayTitle: String,
        url: String,
    ) {
        val saved = CineLibrary.progress(context, key)
        val startMs = if (saved != null && saved.isResumable) {
            (saved.positionMs - 2_000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val intent = Intent(context, MPVActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra("title", displayTitle)
            // MPVActivity writes the resume position under this key.
            putExtra("anime_track", key)
            if (startMs > 0L) putExtra("position", startMs.toInt())
        }

        try {
            context.startActivity(intent)
            CineLibrary.recordPlay(context, title, key, displayTitle)
        } catch (t: Throwable) {
            android.util.Log.e("CineVerse", "Could not start mpv", t)
            Toast.makeText(context, "Could not start the player", Toast.LENGTH_LONG).show()
        }
    }
}
