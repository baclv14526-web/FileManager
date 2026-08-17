package com.filemanager.ui.viewer

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.filemanager.databinding.ActivityImageViewerBinding

class ImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "extra_paths"
        const val EXTRA_INDEX = "extra_index"
    }

    private lateinit var binding: ActivityImageViewerBinding
    private var paths: ArrayList<String> = arrayListOf()
    private var isUiVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        supportActionBar?.hide()

        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        if (paths.isEmpty()) { finish(); return }

        val adapter = ImagePagerAdapter(paths) { toggleUI() }
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(startIndex, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })

        binding.btnClose.setOnClickListener { finish() }
        updateCounter(startIndex)
    }

    private fun updateCounter(position: Int) {
        binding.tvCounter.text = "${position + 1} / ${paths.size}"
        val path = paths[position]
        binding.tvFileName.text = path.substringAfterLast("/")
    }

    private fun toggleUI() {
        isUiVisible = !isUiVisible
        val visibility = if (isUiVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = visibility
        binding.bottomBar.visibility = visibility
        if (isUiVisible) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }
}
