package com.filemanager.utils

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

@GlideModule
class AppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // ✅ Giới hạn memory cache 48MB (thay vì auto ~25% heap)
        val memoryCacheSizeBytes = 1024 * 1024 * 48L
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes))

        // ✅ Disk cache 150MB
        builder.setDiskCache(
            InternalCacheDiskCacheFactory(context, 1024 * 1024 * 150L)
        )

        // ✅ Default options: RGB_565 cho toàn app
        builder.setDefaultRequestOptions(
            RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        )
    }

    // ✅ Tắt manifest parsing để tránh conflict với các thư viện khác
    override fun isManifestParsingEnabled() = false
}
