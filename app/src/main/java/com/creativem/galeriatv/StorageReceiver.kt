import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class StorageReceiver(private val onStorageChanged: () -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // Se ejecuta cuando hay un cambio en el almacenamiento (USB conectado/desconectado)
        onStorageChanged()
    }

    // Registra el receiver para escuchar los eventos de almacenamiento
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addDataScheme("file")
        }
        context.registerReceiver(this, filter)
    }

    // Desregistra el receiver para evitar fugas de memoria
    fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }
}