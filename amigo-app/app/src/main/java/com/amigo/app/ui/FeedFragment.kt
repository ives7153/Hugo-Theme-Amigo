package com.amigo.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.amigo.app.PostEditorActivity
import com.amigo.app.R
import com.amigo.app.data.AppDatabase
import com.amigo.app.data.Comment
import com.amigo.app.data.PrefKeys
import com.amigo.app.data.SettingsRepository
import com.amigo.app.databinding.FragmentFeedBinding
import com.amigo.app.util.AvatarLoader
import kotlinx.coroutines.launch

class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { AppDatabase.get(requireContext()) }
    private val settings by lazy { SettingsRepository(db.settingDao()) }
    private lateinit var adapter: FeedAdapter
    private var ownerAvatar: String? = null

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scope = viewLifecycleOwner.lifecycleScope
        adapter = FeedAdapter(
            context = requireContext(),
            scope = scope,
            ownerAvatar = ownerAvatar,
            onLike = { post -> scope.launch { toggleLike(post.id) } },
            onComment = { post -> openComments(post.id) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // 微信风格：点封面/头像/名字 发朋友圈
        binding.ownerAvatar.setOnClickListener { openEditor() }
        binding.ownerName.setOnClickListener { openEditor() }
        binding.btnFirstPost.setOnClickListener { openEditor() }

        observeFeed()
        viewLifecycleOwner.lifecycleScope.launch { refreshProfile() }
    }

    private fun observeFeed() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                db.postDao().observeAll().collect { posts ->
                    val images = db.imageDao().getAll()
                    val comments = db.commentDao().getAll()
                    val items = posts.map { p ->
                        FeedItem(
                            post = p,
                            images = images.filter { it.postId == p.id }.sortedBy { it.sort },
                            comments = comments.filter { it.postId == p.id }.sortedBy { it.createdAt }
                        )
                    }
                    adapter.submit(items)
                    binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun refreshProfile() {
        val name = settings.getString(PrefKeys.OWNER_NAME, "我")
        ownerAvatar = settings.getString(PrefKeys.OWNER_AVATAR)
        binding.ownerName.text = name
        AvatarLoader.load(requireContext(), binding.ownerAvatar, ownerAvatar, name)
        val count = db.postDao().getAll().size
        binding.ownerSub.text = "共 $count 条朋友圈"
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch { refreshProfile() }
    }

    private fun openEditor() {
        editorLauncher.launch(Intent(requireContext(), PostEditorActivity::class.java))
    }

    private suspend fun toggleLike(postId: Long) {
        val ownerName = settings.getString(PrefKeys.OWNER_NAME, "我")
        val ownerAvatarPath = settings.getString(PrefKeys.OWNER_AVATAR)
        val existing = db.commentDao().getByPost(postId).firstOrNull { !it.isAI && it.type == "like" }
        if (existing != null) {
            db.commentDao().delete(existing)
            db.postDao().setLiked(postId, false)
        } else {
            db.commentDao().insert(
                Comment(
                    postId = postId,
                    authorName = ownerName,
                    authorAvatar = ownerAvatarPath.ifEmpty { null },
                    content = "❤️",
                    createdAt = System.currentTimeMillis(),
                    isAI = false,
                    type = "like"
                )
            )
            db.postDao().setLiked(postId, true)
        }
    }

    private fun openComments(postId: Long) {
        val sheet = CommentSheetDialog(requireContext(), postId)
        sheet.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}