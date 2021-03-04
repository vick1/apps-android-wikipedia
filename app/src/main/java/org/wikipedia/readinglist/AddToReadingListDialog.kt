package org.wikipedia.readinglist

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.wikipedia.Constants
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.analytics.ReadingListsFunnel
import org.wikipedia.databinding.DialogAddToReadingListBinding
import org.wikipedia.page.ExtendedBottomSheetDialogFragment
import org.wikipedia.page.PageTitle
import org.wikipedia.readinglist.ReadingListTitleDialog.readingListTitleDialog
import org.wikipedia.readinglist.database.ReadingList
import org.wikipedia.readinglist.database.ReadingListDbHelper
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.SiteInfoClient
import org.wikipedia.util.DimenUtil.getDimension
import org.wikipedia.util.DimenUtil.roundedDpToPx
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.FeedbackUtil.makeSnackbar
import org.wikipedia.util.log.L
import java.util.*

open class AddToReadingListDialog : ExtendedBottomSheetDialogFragment() {
    private var _binding: DialogAddToReadingListBinding? = null
    val binding get() = _binding!!

    private lateinit var titles: List<PageTitle>
    private lateinit var adapter: ReadingListAdapter
    private val createClickListener = CreateButtonClickListener()
    private var showDefaultList = false
    private val displayedLists = mutableListOf<ReadingList>()
    private val listItemCallback = ReadingListItemCallback()
    var readingLists = listOf<ReadingList>()
    var invokeSource: InvokeSource? = null
    var disposables = CompositeDisposable()
    var dismissListener: DialogInterface.OnDismissListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titles = requireArguments().getParcelableArrayList(PAGE_TITLE_LIST)!!
        invokeSource = requireArguments().getSerializable(Constants.INTENT_EXTRA_INVOKE_SOURCE) as InvokeSource?
        showDefaultList = requireArguments().getBoolean(SHOW_DEFAULT_LIST)
        adapter = ReadingListAdapter()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        _binding = DialogAddToReadingListBinding.inflate(inflater, container, false)
        binding.listOfLists.layoutManager = LinearLayoutManager(requireActivity())
        binding.listOfLists.adapter = adapter
        binding.createButton.setOnClickListener(createClickListener)

        // Log a click event, but only the first time the dialog is shown.
        logClick(savedInstanceState)
        binding.onboardingContainer.root.visibility = View.GONE
        binding.listsContainer.visibility = View.GONE
        updateLists()
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        BottomSheetBehavior.from(binding.root.parent as View).peekHeight = roundedDpToPx(getDimension(R.dimen.readingListSheetPeekHeight))
    }

    override fun onDestroyView() {
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun dismiss() {
        super.dismiss()
        dismissListener?.onDismiss(null)
    }

    private fun checkAndShowOnboarding() {
        var isOnboarding = Prefs.isReadingListTutorialEnabled()
        if (isOnboarding) {
            // Don't show onboarding message if the user already has items in lists (i.e. from syncing).
            readingLists.forEach {
                if (it.pages().isNotEmpty()) {
                    isOnboarding = false
                    Prefs.setReadingListTutorialEnabled(false)
                    return
                }
            }
        }
        binding.onboardingContainer.onboardingButton.setOnClickListener {
            binding.onboardingContainer.root.visibility = View.GONE
            binding.listsContainer.visibility = View.VISIBLE
            Prefs.setReadingListTutorialEnabled(false)
            if (displayedLists.isEmpty()) {
                showCreateListDialog()
            }
        }
        binding.listsContainer.visibility = if (isOnboarding) View.GONE else View.VISIBLE
        binding.onboardingContainer.root.visibility = if (isOnboarding) View.VISIBLE else View.GONE
    }

    private fun updateLists() {
        disposables.add(Observable.fromCallable { ReadingListDbHelper.instance().allLists }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ lists ->
                    readingLists = lists
                    displayedLists.clear()
                    displayedLists.addAll(readingLists)
                    if (!showDefaultList && displayedLists.isNotEmpty()) {
                        displayedLists.removeAt(0)
                    }
                    ReadingList.sort(displayedLists, Prefs.getReadingListSortMode(ReadingList.SORT_BY_NAME_ASC))
                    adapter.notifyDataSetChanged()
                    checkAndShowOnboarding()
                }) { obj: Throwable -> L.w(obj) })
    }

    private inner class CreateButtonClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            if (readingLists.size >= Constants.MAX_READING_LISTS_LIMIT) {
                val message = getString(R.string.reading_lists_limit_message)
                dismiss()
                makeSnackbar(requireActivity(), message, FeedbackUtil.LENGTH_DEFAULT).show()
            } else {
                showCreateListDialog()
            }
        }
    }

    private fun showCreateListDialog() {
        val existingTitles: MutableList<String?> = ArrayList()
        for (tempList in readingLists) {
            existingTitles.add(tempList.title())
        }
        readingListTitleDialog(requireActivity(), "", "",
                existingTitles) { text: String?, description: String? ->
            val list = ReadingListDbHelper.instance().createList(text!!, description)
            addAndDismiss(list, titles)
        }.show()
    }

    private fun addAndDismiss(readingList: ReadingList, titles: List<PageTitle>?) {
        if (readingList.pages().size + titles!!.size > SiteInfoClient.getMaxPagesPerReadingList()) {
            val message = getString(R.string.reading_list_article_limit_message, readingList.title(), SiteInfoClient.getMaxPagesPerReadingList())
            makeSnackbar(requireActivity(), message, FeedbackUtil.LENGTH_DEFAULT).show()
            dismiss()
            return
        }
        commitChanges(readingList, titles)
    }

    open fun logClick(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            ReadingListsFunnel().logAddClick(invokeSource)
        }
    }

    open fun commitChanges(readingList: ReadingList, titles: List<PageTitle>) {
        disposables.add(Observable.fromCallable { ReadingListDbHelper.instance().addPagesToListIfNotExist(readingList, titles) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ addedTitlesList: List<String> ->
                    val message: String
                    if (addedTitlesList.isEmpty()) {
                        message = if (titles.size == 1) getString(R.string.reading_list_article_already_exists_message, readingList.title(), titles[0].displayText) else getString(R.string.reading_list_articles_already_exist_message, readingList.title())
                    } else {
                        message = if (addedTitlesList.size == 1) getString(R.string.reading_list_article_added_to_named, addedTitlesList[0], readingList.title()) else getString(R.string.reading_list_articles_added_to_named, addedTitlesList.size, readingList.title())
                        ReadingListsFunnel().logAddToList(readingList, readingLists.size, invokeSource)
                    }
                    showViewListSnackBar(readingList, message)
                    dismiss()
                }) { obj: Throwable -> L.w(obj) })
    }

    fun showViewListSnackBar(list: ReadingList, message: String) {
        makeSnackbar(requireActivity(), message, FeedbackUtil.LENGTH_DEFAULT)
                .setAction(R.string.reading_list_added_view_button) { v: View -> v.context.startActivity(ReadingListActivity.newIntent(v.context, list)) }.show()
    }

    private inner class ReadingListItemCallback : ReadingListItemView.Callback {
        override fun onClick(readingList: ReadingList) {
            addAndDismiss(readingList, titles)
        }

        override fun onRename(readingList: ReadingList) {}
        override fun onDelete(readingList: ReadingList) {}
        override fun onSaveAllOffline(readingList: ReadingList) {}
        override fun onRemoveAllOffline(readingList: ReadingList) {}
    }

    private class ReadingListItemHolder constructor(itemView: ReadingListItemView) : RecyclerView.ViewHolder(itemView) {
        fun bindItem(readingList: ReadingList) {
            (itemView as ReadingListItemView).setReadingList(readingList, ReadingListItemView.Description.SUMMARY)
        }

        val view: ReadingListItemView
            get() = itemView as ReadingListItemView

        init {
            itemView.isLongClickable = false
        }
    }

    private inner class ReadingListAdapter : RecyclerView.Adapter<ReadingListItemHolder>() {
        override fun getItemCount(): Int {
            return displayedLists.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, pos: Int): ReadingListItemHolder {
            val view = ReadingListItemView(context)
            return ReadingListItemHolder(view)
        }

        override fun onBindViewHolder(holder: ReadingListItemHolder, pos: Int) {
            holder.bindItem(displayedLists[pos])
        }

        override fun onViewAttachedToWindow(holder: ReadingListItemHolder) {
            super.onViewAttachedToWindow(holder)
            holder.view.setCallback(listItemCallback)
        }

        override fun onViewDetachedFromWindow(holder: ReadingListItemHolder) {
            holder.view.setCallback(null)
            super.onViewDetachedFromWindow(holder)
        }
    }

    companion object {
        const val PAGE_TITLE_LIST = "pageTitleList"
        const val SHOW_DEFAULT_LIST = "showDefaultList"

        @JvmStatic
        @JvmOverloads
        fun newInstance(title: PageTitle,
                        source: InvokeSource,
                        listener: DialogInterface.OnDismissListener? = null): AddToReadingListDialog {
            return newInstance(listOf(title), source, listener)
        }

        @JvmStatic
        @JvmOverloads
        fun newInstance(titles: List<PageTitle>,
                        source: InvokeSource,
                        listener: DialogInterface.OnDismissListener? = null): AddToReadingListDialog {
            val dialog = AddToReadingListDialog()
            val args = Bundle()
            args.putParcelableArrayList(PAGE_TITLE_LIST, ArrayList<Parcelable>(titles))
            args.putSerializable(Constants.INTENT_EXTRA_INVOKE_SOURCE, source)
            args.putBoolean(SHOW_DEFAULT_LIST, true)
            dialog.arguments = args
            dialog.dismissListener = listener
            return dialog
        }
    }
}
