package org.wikipedia.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.collection.LruCache
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.wikipedia.LongPressHandler
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.FragmentUtil.getCallback
import org.wikipedia.analytics.SearchFunnel
import org.wikipedia.database.AppDatabase
import org.wikipedia.databinding.FragmentSearchResultsBinding
import org.wikipedia.databinding.ItemSearchNoResultsBinding
import org.wikipedia.databinding.ItemSearchResultBinding
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.history.HistoryEntry
import org.wikipedia.page.PageTitle
import org.wikipedia.readinglist.LongPressMenu
import org.wikipedia.readinglist.database.ReadingListPage
import org.wikipedia.util.L10nUtil.setConditionalLayoutDirection
import org.wikipedia.util.ResourceUtil.getThemedColorStateList
import org.wikipedia.util.StringUtil
import org.wikipedia.views.DefaultViewHolder
import org.wikipedia.views.ViewUtil.formatLangButton
import org.wikipedia.views.ViewUtil.loadImageWithRoundedCorners
import java.util.*
import java.util.concurrent.TimeUnit

class SearchResultsFragment : Fragment() {
    interface Callback {
        fun onSearchAddPageToList(entry: HistoryEntry, addToDefault: Boolean)
        fun onSearchMovePageToList(sourceReadingListId: Long, entry: HistoryEntry)
        fun onSearchProgressBar(enabled: Boolean)
        fun navigateToTitle(item: PageTitle, inNewTab: Boolean, position: Int)
        fun setSearchText(text: CharSequence)
        fun getFunnel(): SearchFunnel
    }

    private var _binding: FragmentSearchResultsBinding? = null
    private val binding get() = _binding!!
    private val viewModel = SearchResultsViewModel()
    private val searchResultsAdapter = SearchResultsAdapter()
    private val searchResultsCache = LruCache<String, MutableList<SearchResult>>(MAX_CACHE_SIZE_SEARCH_RESULTS)
    private val searchResultsCountCache = LruCache<String, List<Int>>(MAX_CACHE_SIZE_SEARCH_RESULTS)
    private var currentSearchTerm: String? = ""
    private var lastFullTextResults: SearchResults? = null
    private val totalResults = mutableListOf<SearchResult>()
    private val resultsCountList = mutableListOf<Int>()
    private val disposables = CompositeDisposable()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchResultsBinding.inflate(inflater, container, false)
        binding.searchResultsList.layoutManager = LinearLayoutManager(requireActivity())
        binding.searchResultsList.adapter = searchResultsAdapter
        binding.searchErrorView.backClickListener = View.OnClickListener { requireActivity().finish() }
        binding.searchErrorView.retryClickListener = View.OnClickListener {
            binding.searchErrorView.visibility = View.GONE
            startSearch(currentSearchTerm, true)
        }

        lifecycleScope.launch {
            viewModel.searchResultsFlow.collectLatest {
                binding.searchResultsList.visibility = View.VISIBLE
                searchResultsAdapter.submitData(it)
            }
        }

        lifecycleScope.launchWhenCreated {
            searchResultsAdapter.loadStateFlow.collectLatest {
                val showEmpty = (it.append is LoadState.NotLoading && it.append.endOfPaginationReached && searchResultsAdapter.itemCount == 0)
                if (showEmpty) {
                    // TODO: show search count adapter
                }
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        binding.searchErrorView.retryClickListener = null
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    fun show() {
        binding.searchResultsDisplay.visibility = View.VISIBLE
    }

    fun hide() {
        binding.searchResultsDisplay.visibility = View.GONE
    }

    val isShowing get() = binding.searchResultsDisplay.visibility == View.VISIBLE

    fun setLayoutDirection(langCode: String) {
        setConditionalLayoutDirection(binding.searchResultsList, langCode)
    }

    fun startSearch(term: String?, force: Boolean) {
        if (!force && currentSearchTerm == term) {
            return
        }
        cancelSearchTask()
        currentSearchTerm = term
        if (term.isNullOrBlank()) {
            clearResults()
            return
        }
        // TODO: load cache in viewModel
//        val cacheResult = searchResultsCache["$searchLanguageCode-$term"]
//        val cacheResultsCount = searchResultsCountCache["$searchLanguageCode-$term"]
//        if (!cacheResult.isNullOrEmpty()) {
//            clearResults()
//            displayResults(cacheResult)
//            return
//        } else if (!cacheResultsCount.isNullOrEmpty()) {
//            clearResults()
//            displayResultsCount(cacheResultsCount)
//            return
//        }
//        doTitlePrefixSearch(term, force)
        viewModel.searchTerm = term
        viewModel.languageCode = searchLanguageCode
        searchResultsAdapter.refresh()
    }

    fun clearSearchResultsCountCache() {
        searchResultsCountCache.evictAll()
    }

    private fun doTitlePrefixSearch(searchTerm: String, force: Boolean) {
        cancelSearchTask()
        val startTime = System.nanoTime()
        updateProgressBar(true)
        disposables.add(Observable.timer(if (force) 0 else DELAY_MILLIS.toLong(), TimeUnit.MILLISECONDS).flatMap {
            Observable.zip(ServiceFactory.get(WikiSite.forLanguageCode(searchLanguageCode)).prefixSearch(searchTerm, BATCH_SIZE, searchTerm),
                    if (searchTerm.length >= 2) Observable.fromCallable { AppDatabase.instance.readingListPageDao().findPageForSearchQueryInAnyList(searchTerm) } else Observable.just(SearchResults()),
                    if (searchTerm.length >= 2) Observable.fromCallable { AppDatabase.instance.historyEntryWithImageDao().findHistoryItem(searchTerm) } else
                        Observable.just(SearchResults())) { searchResponse, readingListSearchResults, historySearchResults ->
                        val searchResults = searchResponse.query?.pages?.let {
                            SearchResults(it, WikiSite.forLanguageCode(searchLanguageCode),
                                searchResponse.continuation)
                        } ?: SearchResults()

                        val resultList = mutableListOf<SearchResult>()
                        addSearchResultsFromTabs(resultList)
                        resultList.addAll(readingListSearchResults.results.filterNot { res ->
                            resultList.map { it.pageTitle.prefixedText }
                                .contains(res.pageTitle.prefixedText)
                        }.take(1))
                        resultList.addAll(historySearchResults.results.filterNot { res ->
                            resultList.map { it.pageTitle.prefixedText }
                                .contains(res.pageTitle.prefixedText)
                        }.take(1))
                        resultList.addAll(searchResults.results)
                        resultList
                    }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doAfterTerminate { updateProgressBar(false) }
                .subscribe({ results ->
                    binding.searchErrorView.visibility = View.GONE
                    handleResults(results, searchTerm, startTime)
                }) { caught ->
                    binding.searchErrorView.visibility = View.VISIBLE
                    binding.searchErrorView.setError(caught)
                    logError(false, startTime)
                })
    }

    private fun addSearchResultsFromTabs(resultList: MutableList<SearchResult>) {
        currentSearchTerm?.let { term ->
            if (term.length < 2) {
                return
            }
            WikipediaApp.instance.tabList.forEach { tab ->
                tab.backStackPositionTitle?.let {
                    if (StringUtil.fromHtml(it.displayText).toString().lowercase(Locale.getDefault()).contains(term.lowercase(Locale.getDefault()))) {
                        resultList.add(SearchResult(it, SearchResult.SearchResultType.TAB_LIST))
                        return
                    }
                }
            }
        }
    }

    private fun handleResults(resultList: MutableList<SearchResult>, searchTerm: String, startTime: Long) {
        // To ease data analysis and better make the funnel track with user behaviour,
        // only transmit search results events if there are a nonzero number of results
        if (resultList.isNotEmpty()) {
            clearResults()
            displayResults(resultList)
            log(resultList, startTime)
        }

        // add titles to cache...
        searchResultsCache.put("$searchLanguageCode-$searchTerm", resultList)

        // scroll to top, but post it to the message queue, because it should be done
        // after the data set is updated.
        binding.searchResultsList.post {
            if (!isAdded) {
                return@post
            }
            binding.searchResultsList.scrollToPosition(0)
        }
        if (resultList.isEmpty()) {
            // kick off full text search if we get no results
            doFullTextSearch(currentSearchTerm, null, true)
        }
    }

    private fun cancelSearchTask() {
        updateProgressBar(false)
    }

    private fun doFullTextSearch(searchTerm: String?,
                                 continuation: MwQueryResponse.Continuation?,
                                 clearOnSuccess: Boolean) {
        val startTime = System.nanoTime()
        updateProgressBar(true)
        disposables.add(ServiceFactory.get(WikiSite.forLanguageCode(searchLanguageCode)).fullTextSearchMedia(searchTerm, BATCH_SIZE,
                continuation?.continuation, continuation?.gsroffset?.toString())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { response ->
                    response.query?.pages?.let {
                        // noinspection ConstantConditions
                        return@map SearchResults(it, WikiSite.forLanguageCode(searchLanguageCode), response.continuation)
                    }
                    SearchResults()
                }
                .flatMap { results ->
                    val resultList = results.results
                    cache(resultList, searchTerm!!)
                    log(resultList, startTime)
                    if (clearOnSuccess) {
                        clearResults()
                    }
                    binding.searchErrorView.visibility = View.GONE

                    // full text special:
                    lastFullTextResults = results
                    if (resultList.isNotEmpty()) {
                        displayResults(resultList)
                    } else {
                        updateProgressBar(true)
                    }
                    if (resultList.isEmpty()) doSearchResultsCountObservable(searchTerm) else Observable.empty()
                }
                .toList()
                .doAfterTerminate { updateProgressBar(false) }
                .subscribe({ list ->
                    var resultsCount = list
                    if (resultsCount.isNotEmpty()) {

                        // make a singleton list if all results are empty.
                        var sum = 0
                        for (count in resultsCount) {
                            sum += count
                            if (sum > 0) {
                                break
                            }
                        }
                        if (sum == 0) {
                            resultsCount = listOf(0)
                        }
                        searchResultsCountCache.put("$searchLanguageCode-$searchTerm", resultsCount)
                        displayResultsCount(resultsCount)
                    }
                }) {
                    // If there's an error, just log it and let the existing prefix search results be.
                    logError(true, startTime)
                })
    }

    private fun doSearchResultsCountObservable(searchTerm: String?): Observable<Int> {
        return Observable.fromIterable(WikipediaApp.instance.languageState.appLanguageCodes)
                .concatMap { langCode ->
                    if (langCode == searchLanguageCode) {
                        return@concatMap Observable.just(MwQueryResponse())
                    }
                    ServiceFactory.get(WikiSite.forLanguageCode(langCode)).prefixSearch(searchTerm, BATCH_SIZE, searchTerm)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .flatMap { response ->
                                response.query?.pages?.let {
                                    return@flatMap Observable.just(response)
                                }
                                ServiceFactory.get(WikiSite.forLanguageCode(langCode)).fullTextSearchMedia(searchTerm, BATCH_SIZE, null, null)
                            }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { response -> response.query?.pages?.size ?: 0 }
    }

    private fun updateProgressBar(enabled: Boolean) {
        callback()?.onSearchProgressBar(enabled)
    }

    private fun clearResults() {
        binding.searchResultsList.visibility = View.GONE
        binding.searchErrorView.visibility = View.GONE
        binding.searchErrorView.visibility = View.GONE
        lastFullTextResults = null
        totalResults.clear()
        resultsCountList.clear()
        binding.searchResultsList.adapter?.notifyDataSetChanged()
    }

    private fun displayResults(results: List<SearchResult>) {
        // TODO: add this logic into viewModel
        for (newResult in results) {
            val res = totalResults.find { newResult.pageTitle == it.pageTitle }
            if (res == null) {
                totalResults.add(newResult)
            } else if (!newResult.pageTitle.description.isNullOrEmpty()) {
                res.pageTitle.description = newResult.pageTitle.description
            }
        }
        binding.searchResultsList.visibility = View.VISIBLE
        binding.searchResultsList.adapter?.notifyDataSetChanged()
    }

    private fun displayResultsCount(list: List<Int>) {
        resultsCountList.clear()
        resultsCountList.addAll(list)
        binding.searchResultsList.visibility = View.VISIBLE
        binding.searchResultsList.adapter?.notifyDataSetChanged()
    }

    private inner class SearchResultsFragmentLongPressHandler(private val lastPositionRequested: Int) : LongPressMenu.Callback {
        override fun onOpenLink(entry: HistoryEntry) {
            callback()?.navigateToTitle(entry.title, false, lastPositionRequested)
        }

        override fun onOpenInNewTab(entry: HistoryEntry) {
            callback()?.navigateToTitle(entry.title, true, lastPositionRequested)
        }

        override fun onAddRequest(entry: HistoryEntry, addToDefault: Boolean) {
            callback()?.onSearchAddPageToList(entry, addToDefault)
        }

        override fun onMoveRequest(page: ReadingListPage?, entry: HistoryEntry) {
            page.let {
                callback()?.onSearchMovePageToList(page!!.listId, entry)
            }
        }
    }

    private inner class SearchResultsDiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem.pageTitle.prefixedText == newItem.pageTitle.prefixedText &&
                        oldItem.pageTitle.namespace == newItem.pageTitle.namespace
        }

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return areItemsTheSame(oldItem, newItem)
        }
    }

    private inner class SearchResultsAdapter : PagingDataAdapter<SearchResult, DefaultViewHolder<View>>(SearchResultsDiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DefaultViewHolder<View> {
            return SearchResultItemViewHolder(ItemSearchResultBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: DefaultViewHolder<View>, pos: Int) {
            getItem(pos)?.let {
                (holder as SearchResultItemViewHolder).bindItem(it)
            }
        }
    }

    private inner class NoSearchResultItemViewHolder(val itemBinding: ItemSearchNoResultsBinding) : DefaultViewHolder<View>(itemBinding.root) {
        private val accentColorStateList = getThemedColorStateList(requireContext(), R.attr.colorAccent)
        private val secondaryColorStateList = getThemedColorStateList(requireContext(), R.attr.material_theme_secondary_color)
        fun bindItem(position: Int) {
            val langCode = WikipediaApp.instance.languageState.appLanguageCodes[position]
            val resultsCount = resultsCountList[position]
            itemBinding.resultsText.text = if (resultsCount == 0) getString(R.string.search_results_count_zero) else resources.getQuantityString(R.plurals.search_results_count, resultsCount, resultsCount)
            itemBinding.resultsText.setTextColor(if (resultsCount == 0) secondaryColorStateList else accentColorStateList)
            itemBinding.languageCode.visibility = if (resultsCountList.size == 1) View.GONE else View.VISIBLE
            itemBinding.languageCode.text = langCode
            itemBinding.languageCode.setTextColor(if (resultsCount == 0) secondaryColorStateList else accentColorStateList)
            ViewCompat.setBackgroundTintList(itemBinding.languageCode, if (resultsCount == 0) secondaryColorStateList else accentColorStateList)
            formatLangButton(itemBinding.languageCode, langCode,
                    SearchFragment.LANG_BUTTON_TEXT_SIZE_SMALLER, SearchFragment.LANG_BUTTON_TEXT_SIZE_LARGER)
            view.isEnabled = resultsCount > 0
            view.setOnClickListener {
                if (!isAdded) {
                    return@setOnClickListener
                }
                (requireParentFragment() as SearchFragment).setUpLanguageScroll(position)
            }
        }
    }

    private inner class SearchResultItemViewHolder(val itemBinding: ItemSearchResultBinding) : DefaultViewHolder<View>(itemBinding.root) {
        fun bindItem(searchResult: SearchResult) {
            val (pageTitle, redirectFrom, type) = searchResult
            if (redirectFrom.isNullOrEmpty()) {
                itemBinding.pageListItemRedirect.visibility = View.GONE
                itemBinding.pageListItemRedirectArrow.visibility = View.GONE
                itemBinding.pageListItemDescription.text = pageTitle.description
            } else {
                itemBinding.pageListItemRedirect.visibility = View.VISIBLE
                itemBinding.pageListItemRedirectArrow.visibility = View.VISIBLE
                itemBinding.pageListItemRedirect.text = getString(R.string.search_redirect_from, redirectFrom)
                itemBinding.pageListItemDescription.visibility = View.GONE
            }
            if (type === SearchResult.SearchResultType.SEARCH) {
                itemBinding.pageListIcon.visibility = View.GONE
            } else {
                itemBinding.pageListIcon.visibility = View.VISIBLE
                itemBinding.pageListIcon.setImageResource(if (type === SearchResult.SearchResultType.HISTORY) R.drawable.ic_history_24 else if (type === SearchResult.SearchResultType.TAB_LIST) R.drawable.ic_tab_one_24px else R.drawable.ic_bookmark_white_24dp)
            }

            // highlight search term within the text
            StringUtil.boldenKeywordText(itemBinding.pageListItemTitle, pageTitle.displayText, currentSearchTerm)
            itemBinding.pageListItemImage.visibility = if (pageTitle.thumbUrl.isNullOrEmpty()) if (type === SearchResult.SearchResultType.SEARCH) View.GONE else View.INVISIBLE else View.VISIBLE
            loadImageWithRoundedCorners(itemBinding.pageListItemImage, pageTitle.thumbUrl)

            // ...and lastly, if we've scrolled to the last item in the list, then
            // continue searching!
            if (position == totalResults.size - 1 && WikipediaApp.instance.isOnline) {
                if (lastFullTextResults == null) {
                    // the first full text search
                    doFullTextSearch(currentSearchTerm, null, false)
                } else if (lastFullTextResults!!.continuation != null) {
                    // subsequent full text searches
                    doFullTextSearch(currentSearchTerm, lastFullTextResults!!.continuation, false)
                }
            }
            view.isLongClickable = true
            view.setOnClickListener {
                if (position < totalResults.size) {
                    callback()?.navigateToTitle(totalResults[position].pageTitle, false, position)
                }
            }
            view.setOnCreateContextMenuListener(LongPressHandler(view,
                    HistoryEntry.SOURCE_SEARCH, SearchResultsFragmentLongPressHandler(position), pageTitle))
        }
    }

    private fun cache(resultList: List<SearchResult>, searchTerm: String) {
        val cacheKey = "$searchLanguageCode-$searchTerm"
        searchResultsCache[cacheKey]?.let {
            it.addAll(resultList)
            searchResultsCache.put(cacheKey, it)
        }
    }

    private fun log(resultList: List<SearchResult>, startTime: Long) {
        // To ease data analysis and better make the funnel track with user behaviour,
        // only transmit search results events if there are a nonzero number of results
        if (resultList.isNotEmpty()) {
            // noinspection ConstantConditions
            callback()?.getFunnel()?.searchResults(true, resultList.size, displayTime(startTime), searchLanguageCode)
        }
    }

    private fun logError(fullText: Boolean, startTime: Long) {
        // noinspection ConstantConditions
        callback()?.getFunnel()?.searchError(fullText, displayTime(startTime), searchLanguageCode)
    }

    private fun displayTime(startTime: Long): Int {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime).toInt()
    }

    private fun callback(): Callback? {
        return getCallback(this, Callback::class.java)
    }

    private val searchLanguageCode get() =
        if (isAdded) (requireParentFragment() as SearchFragment).searchLanguageCode else WikipediaApp.instance.languageState.appLanguageCode

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_NO_RESULTS = 1
        private const val BATCH_SIZE = 20
        private const val DELAY_MILLIS = 300
        private const val MAX_CACHE_SIZE_SEARCH_RESULTS = 4
    }
}
