package com.creativem.galeriatv.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.creativem.galeriatv.MainActivity
import com.creativem.galeriatv.databinding.DialogConfigBinding
import com.creativem.galeriatv.R

class ConfigDialogFragment : DialogFragment() {

    private var _binding: DialogConfigBinding? = null
    private val binding get() = _binding!!

    // Interfaces opcionales
    interface OnColumnChangeListener {
        fun onColumnCountSelected(columnCount: Int)
    }
    interface OnFolderChangeListener {
        fun onFolderSelected()
    }

    private var columnListener: OnColumnChangeListener? = null
    private var folderListener: OnFolderChangeListener? = null

    fun setOnColumnChangeListener(listener: OnColumnChangeListener) {
        columnListener = listener
    }

    fun setOnFolderChangeListener(listener: OnFolderChangeListener) {
        folderListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Cerrar diálogo
        binding.btnCerrar.setOnClickListener { dismiss() }

        // Cambio de columnas
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentColumns = prefs.getInt("grid_columns", 1)
        val opciones = (1..8).map {
            if (it == currentColumns) "$it ítems por fila ✅" else "$it ítems por fila"
        }.toTypedArray()

        binding.btnCambiarColumnas.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Selecciona tamaño de cuadrícula")
                .setItems(opciones) { _, index ->
                    val columnasSeleccionadas = index + 1
                    prefs.edit().putInt("grid_columns", columnasSeleccionadas).apply()
                    columnListener?.onColumnCountSelected(columnasSeleccionadas)
                }
                .show()
        }

        // Cambiar carpeta predeterminada
        binding.CarpetaPredeterminada.setOnClickListener {
            folderListener?.onFolderSelected()
            dismiss()
        }

        // 🔹 Botón para seleccionar reproductor de video
        binding.selecionarReproductor.setOnClickListener {
            (activity as? MainActivity)?.let { main ->
                main.selectDefaultVideoPlayer()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
