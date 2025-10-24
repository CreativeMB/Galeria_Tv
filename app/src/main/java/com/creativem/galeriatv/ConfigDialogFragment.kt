package com.creativem.galeriatv.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import com.creativem.galeriatv.databinding.DialogConfigBinding
import com.creativem.galeriatv.R

class ConfigDialogFragment : DialogFragment() {
    interface OnColumnChangeListener {
        fun onColumnCountSelected(columnCount: Int)
    }

    private var _binding: DialogConfigBinding? = null
    private val binding get() = _binding!!
    private var listener: OnColumnChangeListener? = null

    fun setOnColumnChangeListener(l: OnColumnChangeListener) {
        listener = l
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
        binding.btnCerrar.setOnClickListener { dismiss() }

        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentColumns = prefs.getInt("grid_columns", 1) // valor por defecto

        val opciones = (1..8).map {
            if (it == currentColumns) "$it ítems por fila ✅" else "$it ítems por fila"
        }.toTypedArray()

        binding.btnCambiarColumnas.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Selecciona tamaño de cuadrícula")
                .setItems(opciones) { _, index ->
                    val columnasSeleccionadas = index + 1
                    prefs.edit().putInt("grid_columns", columnasSeleccionadas).apply()
                    listener?.onColumnCountSelected(columnasSeleccionadas)
                    dismiss()
                }
                .show()
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