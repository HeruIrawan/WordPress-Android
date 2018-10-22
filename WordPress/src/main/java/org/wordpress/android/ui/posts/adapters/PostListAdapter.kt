package org.wordpress.android.ui.posts.adapters

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v4.util.SparseArrayCompat
import android.support.v7.widget.RecyclerView
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.ProgressBar
import android.widget.TextView
import org.apache.commons.lang3.StringUtils
import org.apache.commons.text.StringEscapeUtils
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.MediaActionBuilder
import org.wordpress.android.fluxc.model.MediaModel
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.post.PostStatus
import org.wordpress.android.fluxc.store.MediaStore
import org.wordpress.android.fluxc.store.MediaStore.MediaPayload
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.UploadStore
import org.wordpress.android.ui.posts.PostListFragment
import org.wordpress.android.ui.posts.PostUtils
import org.wordpress.android.ui.prefs.AppPrefs
import org.wordpress.android.ui.reader.utils.ReaderImageScanner
import org.wordpress.android.ui.reader.utils.ReaderUtils
import org.wordpress.android.ui.uploads.UploadService
import org.wordpress.android.ui.uploads.UploadUtils
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.DisplayUtils
import org.wordpress.android.util.ImageUtils
import org.wordpress.android.util.SiteUtils
import org.wordpress.android.util.image.ImageManager
import org.wordpress.android.util.image.ImageType
import org.wordpress.android.widgets.PostListButton
import java.util.ArrayList
import javax.inject.Inject

private const val ROW_ANIM_DURATION: Long = 150
private const val MAX_DISPLAYED_UPLOAD_PROGRESS = 90

private const val VIEW_TYPE_POST = 0
private const val VIEW_TYPE_ENDLIST_INDICATOR = 1

/**
 * Adapter for Posts/Pages list
 */
class PostListAdapter(
    context: Context,
    private val site: SiteModel
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var onLoadMoreListener: OnLoadMoreListener? = null
    private var onPostsLoadedListener: OnPostsLoadedListener? = null
    private var onPostSelectedListener: OnPostSelectedListener? = null
    private var onPostButtonClickListener: OnPostButtonClickListener? = null
    private val photonWidth: Int
    private val photonHeight: Int
    private val endlistIndicatorHeight: Int

    private val isStatsSupported: Boolean
    private val showAllButtons: Boolean

    private var isLoadingPosts: Boolean = false

    private val posts = ArrayList<PostModel>()
    private val hiddenPosts = ArrayList<PostModel>()
    private val featuredImageUrls = SparseArrayCompat<String>()

    private var recyclerView: RecyclerView? = null
    private val layoutInflater: LayoutInflater

    @Inject internal lateinit var dispatcher: Dispatcher
    @Inject internal lateinit var postStore: PostStore
    @Inject internal lateinit var mediaStore: MediaStore
    @Inject internal lateinit var uploadStore: UploadStore
    @Inject internal lateinit var imageManager: ImageManager

    interface OnPostButtonClickListener {
        fun onPostButtonClicked(buttonType: Int, postClicked: PostModel)
    }

    enum class LoadMode {
        IF_CHANGED,
        FORCED
    }

    init {
        (context.applicationContext as WordPress).component().inject(this)

        layoutInflater = LayoutInflater.from(context)
        isStatsSupported = SiteUtils.isAccessedViaWPComRest(site) && site.hasCapabilityViewStats

        val displayWidth = DisplayUtils.getDisplayPixelWidth(context)
        val contentSpacing = context.resources.getDimensionPixelSize(R.dimen.content_margin)
        photonWidth = displayWidth - contentSpacing * 2
        photonHeight = context.resources.getDimensionPixelSize(R.dimen.reader_featured_image_height)

        // endlist indicator height is hard-coded here so that its horizontal line is in the middle of the fab
        endlistIndicatorHeight = DisplayUtils.dpToPx(context, 74)

        // on larger displays we can always show all buttons
        showAllButtons = displayWidth >= 1080
    }

    fun setOnLoadMoreListener(listener: OnLoadMoreListener) {
        onLoadMoreListener = listener
    }

    fun setOnPostsLoadedListener(listener: OnPostsLoadedListener) {
        onPostsLoadedListener = listener
    }

    fun setOnPostSelectedListener(listener: OnPostSelectedListener) {
        onPostSelectedListener = listener
    }

    fun setOnPostButtonClickListener(listener: OnPostButtonClickListener) {
        onPostButtonClickListener = listener
    }

    private fun getItem(position: Int): PostModel? {
        return if (isValidPostPosition(position)) {
            posts[position]
        } else null
    }

    private fun isValidPostPosition(position: Int): Boolean {
        return position >= 0 && position < posts.size
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == posts.size) {
            VIEW_TYPE_ENDLIST_INDICATOR
        } else VIEW_TYPE_POST
    }

    override fun getItemCount(): Int {
        return if (posts.size == 0) {
            0
        } else {
            posts.size + 1 // +1 for the endlist indicator
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_ENDLIST_INDICATOR) {
            val view = layoutInflater.inflate(R.layout.endlist_indicator, parent, false)
            view.layoutParams.height = endlistIndicatorHeight
            EndListViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.post_cardview, parent, false)
            PostViewHolder(view)
        }
    }

    private fun canShowStatsForPost(post: PostModel): Boolean {
        return (isStatsSupported && PostStatus.fromPost(post) == PostStatus.PUBLISHED &&
                !post.isLocalDraft && !post.isLocallyChanged)
    }

    private fun canPublishPost(post: PostModel?): Boolean {
        return (post != null && !UploadService.isPostUploadingOrQueued(post) &&
                (post.isLocallyChanged || post.isLocalDraft || PostStatus.fromPost(post) == PostStatus.DRAFT))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // nothing to do if this is the static endlist indicator
        if (getItemViewType(position) == VIEW_TYPE_ENDLIST_INDICATOR) {
            return
        }

        val post = posts[position]
        val context = holder.itemView.context

        if (holder is PostViewHolder) {
            if (StringUtils.isNotEmpty(post.title)) {
                // Unescape HTML
                val cleanPostTitle = StringEscapeUtils.unescapeHtml4(post.title)
                holder.title.text = cleanPostTitle
            } else {
                holder.title.text = context.resources.getText(R.string.untitled_in_parentheses)
            }

            var cleanPostExcerpt = PostUtils.getPostListExcerptFromPost(post)

            if (StringUtils.isNotEmpty(cleanPostExcerpt)) {
                holder.excerpt.visibility = View.VISIBLE
                // Unescape HTML
                cleanPostExcerpt = StringEscapeUtils.unescapeHtml4(cleanPostExcerpt)
                // Collapse short-codes: [gallery ids="1206,1205,1191"] -> [gallery]
                cleanPostExcerpt = PostUtils.collapseShortcodes(cleanPostExcerpt)
                holder.excerpt.text = cleanPostExcerpt
            } else {
                holder.excerpt.visibility = View.GONE
            }

            showFeaturedImage(post.id, holder.featuredImage)

            // local drafts say "delete" instead of "trash"
            if (post.isLocalDraft) {
                holder.date.visibility = View.GONE
                holder.trashButton.buttonType = PostListButton.BUTTON_DELETE
            } else {
                holder.date.text = PostUtils.getFormattedDate(post)
                holder.date.visibility = View.VISIBLE
                holder.trashButton.buttonType = PostListButton.BUTTON_TRASH
            }

            if (UploadService.isPostUploading(post)) {
                holder.disabledOverlay.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = true
            } else if (!AppPrefs.isAztecEditorEnabled() && UploadService.isPostUploadingOrQueued(post)) {
                // Editing posts with uploading media is only supported in Aztec
                holder.disabledOverlay.visibility = View.VISIBLE
            } else {
                holder.progressBar.isIndeterminate = false
                holder.disabledOverlay.visibility = View.GONE
            }

            updateStatusTextAndImage(holder.status, holder.statusImage, post)
            updatePostUploadProgressBar(holder.progressBar, post)
            configurePostButtons(holder, post)
        }

        // load more posts when we near the end
        if (position >= posts.size - 1 && position >= PostListFragment.POSTS_REQUEST_COUNT - 1) {
            onLoadMoreListener?.onLoadMore()
        }

        holder.itemView.setOnClickListener {
            onPostSelectedListener?.onPostSelected(post)
        }
    }

    private fun showFeaturedImage(postId: Int, imgFeatured: ImageView) {
        val imageUrl = featuredImageUrls.get(postId)
        if (imageUrl == null) {
            imgFeatured.visibility = View.GONE
            imageManager.cancelRequestAndClearImageView(imgFeatured)
        } else if (imageUrl.startsWith("http")) {
            val photonUrl = ReaderUtils.getResizedImageUrl(
                    imageUrl, photonWidth, photonHeight, !SiteUtils.isPhotonCapable(site)
            )
            imgFeatured.visibility = View.VISIBLE
            imageManager.load(imgFeatured, ImageType.PHOTO, photonUrl, ScaleType.CENTER_CROP)
        } else {
            val bmp = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                    imgFeatured.context, imageUrl, photonWidth
            )
            if (bmp != null) {
                imgFeatured.visibility = View.VISIBLE
                imageManager.load(imgFeatured, bmp)
            } else {
                imgFeatured.visibility = View.GONE
                imageManager.cancelRequestAndClearImageView(imgFeatured)
            }
        }
    }

    private fun updatePostUploadProgressBar(view: ProgressBar, post: PostModel) {
        if (!uploadStore.isFailedPost(post) && (UploadService.isPostUploadingOrQueued(post) ||
                        UploadService.hasInProgressMediaUploadsForPost(post))) {
            view.visibility = View.VISIBLE
            val overallProgress = Math.round(UploadService.getMediaUploadProgressForPost(post) * 100)
            // Sometimes the progress bar can be stuck at 100% for a long time while further processing happens
            // Cap the progress bar at MAX_DISPLAYED_UPLOAD_PROGRESS (until we move past the 'uploading media' phase)
            view.progress = Math.min(MAX_DISPLAYED_UPLOAD_PROGRESS, overallProgress)
        } else {
            view.visibility = View.GONE
        }
    }

    private fun updateStatusTextAndImage(txtStatus: TextView, imgStatus: ImageView, post: PostModel) {
        val context = txtStatus.context

        if (PostStatus.fromPost(post) == PostStatus.PUBLISHED && !post.isLocalDraft && !post.isLocallyChanged) {
            txtStatus.visibility = View.GONE
            imgStatus.visibility = View.GONE
            imageManager.cancelRequestAndClearImageView(imgStatus)
        } else {
            var statusTextResId = 0
            var statusIconResId = 0
            var statusColorResId = R.color.grey_darken_10
            var errorMessage: String? = null

            val reason = uploadStore.getUploadErrorForPost(post)
            if (reason != null && !UploadService.hasInProgressMediaUploadsForPost(post)) {
                if (reason.mediaError != null) {
                    errorMessage = context.getString(R.string.error_media_recover_post)
                } else if (reason.postError != null) {
                    errorMessage = UploadUtils.getErrorMessageFromPostError(context, post, reason.postError)
                }
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
                statusColorResId = R.color.alert_red
            } else if (UploadService.isPostUploading(post)) {
                statusTextResId = R.string.post_uploading
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (UploadService.hasInProgressMediaUploadsForPost(post)) {
                statusTextResId = R.string.uploading_media
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (UploadService.isPostQueued(post) || UploadService.hasPendingMediaUploadsForPost(post)) {
                // the Post (or its related media if such a thing exist) *is strictly* queued
                statusTextResId = R.string.post_queued
                statusIconResId = R.drawable.ic_gridicons_cloud_upload
            } else if (post.isLocalDraft) {
                statusTextResId = R.string.local_draft
                statusIconResId = R.drawable.ic_gridicons_page
                statusColorResId = R.color.alert_yellow_dark
            } else if (post.isLocallyChanged) {
                statusTextResId = R.string.local_changes
                statusIconResId = R.drawable.ic_gridicons_page
                statusColorResId = R.color.alert_yellow_dark
            } else {
                when (PostStatus.fromPost(post)) {
                    PostStatus.DRAFT -> {
                        statusTextResId = R.string.post_status_draft
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_yellow_dark
                    }
                    PostStatus.PRIVATE -> statusTextResId = R.string.post_status_post_private
                    PostStatus.PENDING -> {
                        statusTextResId = R.string.post_status_pending_review
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_yellow_dark
                    }
                    PostStatus.SCHEDULED -> {
                        statusTextResId = R.string.post_status_scheduled
                        statusIconResId = R.drawable.ic_gridicons_calendar
                        statusColorResId = R.color.blue_medium
                    }
                    PostStatus.TRASHED -> {
                        statusTextResId = R.string.post_status_trashed
                        statusIconResId = R.drawable.ic_gridicons_page
                        statusColorResId = R.color.alert_red
                    }
                    PostStatus.UNKNOWN -> {
                    }
                    PostStatus.PUBLISHED -> {
                    }
                    else ->
                        // no-op
                        return
                }
            }

            val resources = context.resources
            txtStatus.setTextColor(resources.getColor(statusColorResId))
            if (!TextUtils.isEmpty(errorMessage)) {
                txtStatus.text = errorMessage
            } else {
                txtStatus.text = if (statusTextResId != 0) resources.getString(statusTextResId) else ""
            }
            txtStatus.visibility = View.VISIBLE

            var drawable: Drawable? = if (statusIconResId != 0) resources.getDrawable(statusIconResId) else null
            if (drawable != null) {
                drawable = DrawableCompat.wrap(drawable)
                DrawableCompat.setTint(drawable, resources.getColor(statusColorResId))
                imgStatus.visibility = View.VISIBLE
                imageManager.load(imgStatus, drawable)
            } else {
                imgStatus.visibility = View.GONE
                imageManager.cancelRequestAndClearImageView(imgStatus)
            }
        }
    }

    private fun configurePostButtons(
        holder: PostViewHolder,
        post: PostModel
    ) {
        val canRetry = uploadStore.getUploadErrorForPost(post) != null &&
                !UploadService.hasInProgressMediaUploadsForPost(post)
        val canShowViewButton = !canRetry
        val canShowStatsButton = canShowStatsForPost(post)
        val canShowPublishButton = canRetry || canPublishPost(post)

        // publish button is re-purposed depending on the situation
        if (canShowPublishButton) {
            if (!site.hasCapabilityPublishPosts) {
                holder.publishButton.buttonType = PostListButton.BUTTON_SUBMIT
            } else if (canRetry) {
                holder.publishButton.buttonType = PostListButton.BUTTON_RETRY
            } else if (PostStatus.fromPost(post) == PostStatus.SCHEDULED && post.isLocallyChanged) {
                holder.publishButton.buttonType = PostListButton.BUTTON_SYNC
            } else {
                holder.publishButton.buttonType = PostListButton.BUTTON_PUBLISH
            }
        }

        // posts with local changes have preview rather than view button
        if (canShowViewButton) {
            if (post.isLocalDraft || post.isLocallyChanged) {
                holder.viewButton.buttonType = PostListButton.BUTTON_PREVIEW
            } else {
                holder.viewButton.buttonType = PostListButton.BUTTON_VIEW
            }
        }

        // edit is always visible
        holder.editButton.visibility = View.VISIBLE
        holder.viewButton.visibility = if (canShowViewButton) View.VISIBLE else View.GONE

        var numVisibleButtons = 2
        if (canShowViewButton) {
            numVisibleButtons++
        }
        if (canShowPublishButton) {
            numVisibleButtons++
        }
        if (canShowStatsButton) {
            numVisibleButtons++
        }

        // if there's enough room to show all buttons then hide back/more and show stats/trash/publish,
        // otherwise show the more button and hide stats/trash/publish
        if (showAllButtons || numVisibleButtons <= 3) {
            holder.moreButton.visibility = View.GONE
            holder.backButton.visibility = View.GONE
            holder.trashButton.visibility = View.VISIBLE
            holder.statsButton.visibility = if (canShowStatsButton) View.VISIBLE else View.GONE
            holder.publishButton.visibility = if (canShowPublishButton) View.VISIBLE else View.GONE
        } else {
            holder.moreButton.visibility = View.VISIBLE
            holder.backButton.visibility = View.GONE
            holder.trashButton.visibility = View.GONE
            holder.statsButton.visibility = View.GONE
            holder.publishButton.visibility = View.GONE
        }

        val btnClickListener = View.OnClickListener { view ->
            // handle back/more here, pass other actions to activity/fragment
            val buttonType = (view as PostListButton).buttonType
            when (buttonType) {
                PostListButton.BUTTON_MORE -> animateButtonRows(holder, post, false)
                PostListButton.BUTTON_BACK -> animateButtonRows(holder, post, true)
                else -> if (onPostButtonClickListener != null) {
                    onPostButtonClickListener?.onPostButtonClicked(buttonType, post)
                }
            }
        }
        holder.editButton.setOnClickListener(btnClickListener)
        holder.viewButton.setOnClickListener(btnClickListener)
        holder.statsButton.setOnClickListener(btnClickListener)
        holder.trashButton.setOnClickListener(btnClickListener)
        holder.moreButton.setOnClickListener(btnClickListener)
        holder.backButton.setOnClickListener(btnClickListener)
        holder.publishButton.setOnClickListener(btnClickListener)
    }

    /*
     * buttons may appear in two rows depending on display size and number of visible
     * buttons - these rows are toggled through the "more" and "back" buttons - this
     * routine is used to animate the new row in and the old row out
     */
    private fun animateButtonRows(
        holder: PostViewHolder,
        post: PostModel,
        showRow1: Boolean
    ) {
        // first animate out the button row, then show/hide the appropriate buttons,
        // then animate the row layout back in
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0f)
        val animOut = ObjectAnimator.ofPropertyValuesHolder(holder.buttonsLayout, scaleX, scaleY)
        animOut.duration = ROW_ANIM_DURATION
        animOut.interpolator = AccelerateInterpolator()

        animOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // row 1
                holder.editButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                holder.viewButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                holder.moreButton.visibility = if (showRow1) View.VISIBLE else View.GONE
                // row 2
                holder.statsButton.visibility = if (!showRow1 && canShowStatsForPost(post)) View.VISIBLE else View.GONE
                holder.publishButton.visibility = if (!showRow1 && canPublishPost(post)) View.VISIBLE else View.GONE
                holder.trashButton.visibility = if (!showRow1) View.VISIBLE else View.GONE
                holder.backButton.visibility = if (!showRow1) View.VISIBLE else View.GONE

                val updatedScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f)
                val updatedScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f)
                val animIn = ObjectAnimator.ofPropertyValuesHolder(holder.buttonsLayout, updatedScaleX, updatedScaleY)
                animIn.duration = ROW_ANIM_DURATION
                animIn.interpolator = DecelerateInterpolator()
                animIn.start()
            }
        })

        animOut.start()
    }

    fun getPositionForPost(post: PostModel): Int {
        return PostUtils.indexOfPostInList(post, posts)
    }

    fun loadPosts(mode: LoadMode) {
        if (isLoadingPosts) {
            AppLog.d(AppLog.T.POSTS, "post adapter > already loading posts")
        } else {
            LoadPostsTask(mode).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    fun updateProgressForPost(post: PostModel) {
        recyclerView?.let {
            val position = getPositionForPost(post)
            if (position > -1) {
                val viewHolder = it.findViewHolderForAdapterPosition(position)
                if (viewHolder is PostViewHolder) {
                    updatePostUploadProgressBar(viewHolder.progressBar, post)
                }
            }
        }
    }

    /*
     * hides the post - used when the post is trashed by the user but the network request
     * to delete the post hasn't completed yet
     */
    fun hidePost(post: PostModel) {
        hiddenPosts.add(post)

        val position = getPositionForPost(post)
        if (position > -1) {
            posts.removeAt(position)
            if (posts.size > 0) {
                notifyItemRemoved(position)
            } else {
                // we must call notifyDataSetChanged when the only post has been deleted - if we
                // call notifyItemRemoved the recycler will throw an IndexOutOfBoundsException
                // because removing the last post also removes the end list indicator
                notifyDataSetChanged()
            }
        }
    }

    fun unhidePost(post: PostModel) {
        if (hiddenPosts.remove(post)) {
            loadPosts(LoadMode.IF_CHANGED)
        }
    }

    interface OnLoadMoreListener {
        fun onLoadMore()
    }

    interface OnPostSelectedListener {
        fun onPostSelected(post: PostModel)
    }

    interface OnPostsLoadedListener {
        fun onPostsLoaded(postCount: Int)
    }

    private class PostViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val excerpt: TextView = view.findViewById(R.id.text_excerpt)
        val date: TextView = view.findViewById(R.id.text_date)
        val status: TextView = view.findViewById(R.id.text_status)

        val statusImage: ImageView = view.findViewById(R.id.image_status)
        val featuredImage: ImageView = view.findViewById(R.id.image_featured)

        val editButton: PostListButton = view.findViewById(R.id.btn_edit)
        val viewButton: PostListButton = view.findViewById(R.id.btn_view)
        val publishButton: PostListButton = view.findViewById(R.id.btn_publish)
        val moreButton: PostListButton = view.findViewById(R.id.btn_more)
        val statsButton: PostListButton = view.findViewById(R.id.btn_stats)
        val trashButton: PostListButton = view.findViewById(R.id.btn_trash)
        val backButton: PostListButton = view.findViewById(R.id.btn_back)
        val buttonsLayout: ViewGroup = view.findViewById(R.id.layout_buttons)

        val disabledOverlay: View = view.findViewById(R.id.disabled_overlay)
        val progressBar: ProgressBar = view.findViewById(R.id.post_upload_progress)
    }

    private class EndListViewHolder(view: View) : RecyclerView.ViewHolder(view)

    /*
     * called after the media (featured image) for a post has been downloaded - locate the post
     * and set its featured image url to the passed url
     */
    fun mediaChanged(mediaModel: MediaModel) {
        // Multiple posts could have the same featured image
        val indexList = PostUtils.indexesOfFeaturedMediaIdInList(mediaModel.mediaId, posts)
        for (position in indexList) {
            val post = getItem(position)
            if (post != null) {
                val imageUrl = mediaModel.url
                if (imageUrl != null) {
                    featuredImageUrls.put(post.id, imageUrl)
                } else {
                    featuredImageUrls.remove(post.id)
                }
                notifyItemChanged(position)
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class LoadPostsTask internal constructor(private val mLoadMode: LoadMode)
        : AsyncTask<Void, Void, Boolean>() {
        private var mTmpPosts: MutableList<PostModel>? = null
        private val mMediaIdsToUpdate = ArrayList<Long>()

        override fun onPreExecute() {
            super.onPreExecute()
            isLoadingPosts = true
        }

        override fun onCancelled() {
            super.onCancelled()
            isLoadingPosts = false
        }

        override fun doInBackground(vararg nada: Void): Boolean? {
            mTmpPosts = postStore.getPostsForSite(site)

            // Make sure we don't return any hidden posts
            if (hiddenPosts.size > 0) {
                mTmpPosts!!.removeAll(hiddenPosts)
            }

            // Go no further if existing post list is the same
            if (mLoadMode == LoadMode.IF_CHANGED && PostUtils.postListsAreEqual(posts, mTmpPosts)) {
                // Always update the list if there are uploading posts
                var postsAreUploading = false
                for (post in mTmpPosts!!) {
                    if (UploadService.isPostUploadingOrQueued(post)) {
                        postsAreUploading = true
                        break
                    }
                }

                if (!postsAreUploading) {
                    return false
                }
            }

            // Generate the featured image url for each post
            featuredImageUrls.clear()
            val isPrivate = !SiteUtils.isPhotonCapable(site)
            for (post in mTmpPosts!!) {
                var imageUrl: String? = null
                if (post.featuredImageId != 0L) {
                    val media = mediaStore.getSiteMediaWithId(site, post.featuredImageId)
                    if (media != null) {
                        imageUrl = media.url
                    } else {
                        // If the media isn't found it means the featured image info hasn't been added to
                        // the local media library yet, so add to the list of media IDs to request info for
                        mMediaIdsToUpdate.add(post.featuredImageId)
                    }
                } else {
                    imageUrl = ReaderImageScanner(post.content, isPrivate).largestImage
                }
                if (!TextUtils.isEmpty(imageUrl)) {
                    featuredImageUrls.put(post.id, imageUrl)
                }
            }

            return true
        }

        override fun onPostExecute(result: Boolean?) {
            if (result!!) {
                posts.clear()
                mTmpPosts?.let { posts.addAll(it) }
                notifyDataSetChanged()

                if (mMediaIdsToUpdate.size > 0) {
                    for (mediaId in mMediaIdsToUpdate) {
                        val mediaToDownload = MediaModel()
                        mediaToDownload.mediaId = mediaId
                        mediaToDownload.localSiteId = site.id
                        val payload = MediaPayload(site, mediaToDownload)
                        dispatcher.dispatch(MediaActionBuilder.newFetchMediaAction(payload))
                    }
                }
            }

            isLoadingPosts = false

            if (onPostsLoadedListener != null) {
                onPostsLoadedListener?.onPostsLoaded(posts.size)
            }
        }
    }
}