package br.com.lincon.phototool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import br.com.lincon.phototool.state.AppState
import br.com.lincon.phototool.ui.AppCallbacks
import br.com.lincon.phototool.ui.PhotoToolApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoToolApp(AppState(), AppCallbacks(dispatch = {})) }
    }
}
