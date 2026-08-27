package com.amigo.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Character
import com.amigo.app.databinding.ActivityCharacterEditBinding
import com.amigo.app.util.AvatarLoader
import com.amigo.app.util.ImageStore
import kotlinx.coroutines.launch

class CharacterEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCharacterEditBinding
    private val db by lazy { AppDatabase.get(this) }
    private var characterId: Long = 0L
    private var avatarPath: String? = null
    private var isEdit = false

    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    avatarPath = ImageStore.importFromUri(this@CharacterEditActivity, uri)
                    AvatarLoader.load(this@CharacterEditActivity, binding.avatar, avatarPath, binding.nameInput.text.toString().ifEmpty { "友" })
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharacterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        characterId = intent.getLongExtra("character_id", 0L)
        isEdit = characterId != 0L

        binding.btnPickAvatar.setOnClickListener {
            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.activitySeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.activityLabel.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        binding.likeSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.likeLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        if (isEdit) {
            binding.btnDelete.visibility = android.view.View.VISIBLE
            binding.btnDelete.setOnClickListener { confirmDelete() }
            loadCharacter()
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun loadCharacter() {
        lifecycleScope.launch {
            val ch = db.characterDao().getById(characterId) ?: return@launch
            binding.nameInput.setText(ch.name)
            binding.personaInput.setText(ch.persona)
            binding.bestFriendSwitch.isChecked = ch.isBestFriend
            binding.activitySeek.progress = ch.activity.coerceIn(0, 100)
            binding.activityLabel.text = ch.activity.toString()
            binding.likeSeek.progress = (ch.likeRate * 100).toInt().coerceIn(0, 100)
            binding.likeLabel.text = "${(ch.likeRate * 100).toInt()}%"
            avatarPath = ch.avatar
            AvatarLoader.load(this@CharacterEditActivity, binding.avatar, avatarPath, ch.name)
        }
    }

    private fun save() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val persona = binding.personaInput.text.toString().trim()
        val activity = binding.activitySeek.progress
        val likeRate = binding.likeSeek.progress / 100f
        val bestFriend = binding.bestFriendSwitch.isChecked

        lifecycleScope.launch {
            if (isEdit) {
                val ch = db.characterDao().getById(characterId) ?: return@launch
                db.characterDao().update(
                    ch.copy(
                        name = name,
                        persona = persona,
                        avatar = avatarPath,
                        activity = activity,
                        likeRate = likeRate,
                        isBestFriend = bestFriend
                    )
                )
            } else {
                db.characterDao().insert(
                    Character(
                        name = name,
                        persona = persona,
                        avatar = avatarPath,
                        activity = activity,
                        likeRate = likeRate,
                        isBestFriend = bestFriend
                    )
                )
            }
            Toast.makeText(this@CharacterEditActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除角色")
            .setMessage("确定删除这个 AI 角色吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    db.characterDao().getById(characterId)?.let { db.characterDao().delete(it) }
                    Toast.makeText(this@CharacterEditActivity, "已删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}