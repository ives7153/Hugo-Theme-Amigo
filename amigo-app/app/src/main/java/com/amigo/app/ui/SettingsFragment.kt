package com.amigo.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.amigo.app.CharacterEditActivity
import com.amigo.app.R
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Character
import com.amigo.app.data.PrefKeys
import com.amigo.app.data.SettingsRepository
import com.amigo.app.databinding.FragmentSettingsBinding
import com.amigo.app.util.AvatarLoader
import com.amigo.app.util.BackupManager
import com.amigo.app.util.ImageStore
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext()) }
    private val settings by lazy { SettingsRepository(db.settingDao()) }

    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val rel = ImageStore.importFromUri(requireContext(), uri)
                        settings.set(PrefKeys.OWNER_AVATAR, rel)
                        refreshProfile()
                        Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "头像更新失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        BackupManager.export(requireContext(), uri)
                        Toast.makeText(requireContext(), "导出成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val msg = BackupManager.import(requireContext(), uri)
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        reloadCharacters()
                        lifecycleScope.launch { refreshProfile() }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    private val characterEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            reloadCharacters()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPickAvatar.setOnClickListener {
            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnSaveProfile.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val name = binding.ownerNameInput.text.toString().trim().ifEmpty { "我" }
                    settings.set(PrefKeys.OWNER_NAME, name)
                    Toast.makeText(requireContext(), "资料已保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnSaveAi.setOnClickListener {
            lifecycleScope.launch {
                try {
                    settings.set(PrefKeys.LLM_ENDPOINT, binding.llmEndpoint.text.toString().trim())
                    settings.set(PrefKeys.LLM_API_KEY, binding.llmApiKey.text.toString().trim())
                    settings.set(PrefKeys.LLM_MODEL, binding.llmModel.text.toString().trim())
                    settings.set(PrefKeys.LLM_TEMPERATURE, binding.llmTemperature.text.toString().trim().ifEmpty { "0.9" })
                    Toast.makeText(requireContext(), "AI 设置已保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnAddCharacter.setOnClickListener {
            characterEditLauncher.launch(Intent(requireContext(), CharacterEditActivity::class.java))
        }
        binding.btnExport.setOnClickListener {
            exportLauncher.launch("amigo-backup-${System.currentTimeMillis()}.zip")
        }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        binding.aiSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                try {
                    settings.set(PrefKeys.AI_ENABLED, if (checked) "1" else "0")
                } catch (e: Exception) {
                }
            }
        }
        lifecycleScope.launch {
            try {
                refreshProfile()
                refreshAI()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载设置失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        reloadCharacters()
    }

    private suspend fun refreshProfile() {
        val name = settings.getString(PrefKeys.OWNER_NAME, "我")
        binding.ownerNameInput.setText(name)
        AvatarLoader.load(requireContext(), binding.ownerAvatar, settings.getString(PrefKeys.OWNER_AVATAR), name)
    }

    private suspend fun refreshAI() {
        binding.aiSwitch.isChecked = settings.getString(PrefKeys.AI_ENABLED, "1") == "1"
        binding.llmEndpoint.setText(settings.getString(PrefKeys.LLM_ENDPOINT))
        binding.llmApiKey.setText(settings.getString(PrefKeys.LLM_API_KEY))
        binding.llmModel.setText(settings.getString(PrefKeys.LLM_MODEL))
        binding.llmTemperature.setText(settings.getFloat(PrefKeys.LLM_TEMPERATURE, 0.9f).toString())
    }

    private fun reloadCharacters() {
        lifecycleScope.launch {
            try {
                val list = db.characterDao().getAll()
                binding.characterList.removeAllViews()
                if (list.isEmpty()) {
                    val hint = TextView(requireContext())
                    hint.text = "还没有角色，先建一个吧"
                    hint.setTextColor(requireContext().getColor(R.color.muted))
                    hint.textSize = 13f
                    hint.setPadding(0, 12, 0, 0)
                    binding.characterList.addView(hint)
                    return@launch
                }
                list.forEach { ch -> binding.characterList.addView(characterRow(ch)) }
            } catch (e: Exception) {
            }
        }
    }

    private fun characterRow(ch: Character): View {
        val den = resources.displayMetrics.density
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(0, (10 * den).toInt(), 0, (10 * den).toInt())

        val avatar = ImageView(requireContext())
        val size = (40 * den).toInt()
        avatar.layoutParams = LinearLayout.LayoutParams(size, size)
        avatar.setBackgroundResource(R.drawable.bg_avatar)
        row.addView(avatar)
        lifecycleScope.launch { AvatarLoader.load(requireContext(), avatar, ch.avatar, ch.name) }

        val col = LinearLayout(requireContext())
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colLp.marginStart = (10 * den).toInt()
        col.layoutParams = colLp

        val name = TextView(requireContext())
        name.text = ch.name + if (ch.isBestFriend) "（挚友）" else ""
        name.setTextColor(requireContext().getColor(R.color.text))
        name.textSize = 15f
        col.addView(name)

        val sub = TextView(requireContext())
        sub.text = "活跃度 ${ch.activity} · " + ch.persona.ifEmpty { "（未填写人设）" }.take(24)
        sub.setTextColor(requireContext().getColor(R.color.muted))
        sub.textSize = 12f
        col.addView(sub)

        row.addView(col)

        val chevron = TextView(requireContext())
        chevron.text = "›"
        chevron.setTextColor(requireContext().getColor(R.color.muted))
        chevron.textSize = 20f
        row.addView(chevron)

        row.setOnClickListener {
            characterEditLauncher.launch(Intent(requireContext(), CharacterEditActivity::class.java).putExtra("character_id", ch.id))
        }
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}