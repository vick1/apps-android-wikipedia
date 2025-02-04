package org.wikipedia.search;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.LongPressHandler;
import org.wikipedia.R;
import org.wikipedia.WikipediaApp;
import org.wikipedia.activity.FragmentUtil;
import org.wikipedia.analytics.SearchFunnel;
import org.wikipedia.dataclient.ServiceFactory;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.mwapi.MwQueryResponse;
import org.wikipedia.history.HistoryDbHelper;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.page.PageTitle;
import org.wikipedia.page.tabs.Tab;
import org.wikipedia.readinglist.LongPressMenu;
import org.wikipedia.readinglist.database.ReadingListDbHelper;
import org.wikipedia.readinglist.database.ReadingListPage;
import org.wikipedia.util.ResourceUtil;
import org.wikipedia.util.StringUtil;
import org.wikipedia.views.DefaultViewHolder;
import org.wikipedia.views.GoneIfEmptyTextView;
import org.wikipedia.views.ViewUtil;
import org.wikipedia.views.WikiErrorView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.wikipedia.search.SearchFragment.LANG_BUTTON_TEXT_SIZE_LARGER;
import static org.wikipedia.search.SearchFragment.LANG_BUTTON_TEXT_SIZE_SMALLER;
import static org.wikipedia.util.L10nUtil.setConditionalLayoutDirection;

public class SearchResultsFragment extends Fragment {
    public interface Callback {
        void onSearchAddPageToList(HistoryEntry entry, boolean addToDefault);
        void onSearchMovePageToList(long sourceReadingListId, HistoryEntry entry);
        void onSearchProgressBar(boolean enabled);
        void navigateToTitle(@NonNull PageTitle item, boolean inNewTab, int position);
        void setSearchText(@NonNull CharSequence text);
        @NonNull SearchFunnel getFunnel();
    }

    private static final int BATCH_SIZE = 20;
    private static final int DELAY_MILLIS = 300;
    private static final int MAX_CACHE_SIZE_SEARCH_RESULTS = 4;

    @BindView(R.id.search_results_display) View searchResultsDisplay;
    @BindView(R.id.search_results_container) View searchResultsContainer;
    @BindView(R.id.search_results_list) RecyclerView searchResultsList;
    @BindView(R.id.search_error_view) WikiErrorView searchErrorView;
    @BindView(R.id.search_suggestion) TextView searchSuggestion;
    private Unbinder unbinder;

    private final LruCache<String, List<SearchResult>> searchResultsCache = new LruCache<>(MAX_CACHE_SIZE_SEARCH_RESULTS);
    private final LruCache<String, List<Integer>> searchResultsCountCache = new LruCache<>(MAX_CACHE_SIZE_SEARCH_RESULTS);
    private String currentSearchTerm = "";
    @Nullable private SearchResults lastFullTextResults;
    @NonNull private final List<SearchResult> totalResults = new ArrayList<>();
    @NonNull private final List<Integer> resultsCountList = new ArrayList<>();
    private CompositeDisposable disposables = new CompositeDisposable();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search_results, container, false);
        unbinder = ButterKnife.bind(this, view);

        searchResultsList.setLayoutManager(new LinearLayoutManager(getActivity()));
        searchResultsList.setAdapter(new SearchResultAdapter());

        searchErrorView.setBackClickListener(v-> requireActivity().finish());
        searchErrorView.setRetryClickListener(v -> {
            searchErrorView.setVisibility(GONE);
            startSearch(currentSearchTerm, true);
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        searchErrorView.setRetryClickListener(null);
        unbinder.unbind();
        unbinder = null;
        disposables.clear();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @OnClick(R.id.search_suggestion) void onSuggestionClick(View view) {
        Callback callback = callback();
        String suggestion = (String) searchSuggestion.getTag();
        if (callback != null && suggestion != null) {
            callback.getFunnel().searchDidYouMean(getSearchLanguageCode());
            callback.setSearchText(suggestion);
            startSearch(suggestion, true);
        }
    }

    public void show() {
        searchResultsDisplay.setVisibility(VISIBLE);
    }

    public void hide() {
        searchResultsDisplay.setVisibility(GONE);
    }

    public boolean isShowing() {
        return searchResultsDisplay.getVisibility() == VISIBLE;
    }

    public void setLayoutDirection(@NonNull String langCode) {
        setConditionalLayoutDirection(searchResultsList, langCode);
    }

    /**
     * Kick off a search, based on a given search term.
     * @param term Phrase to search for.
     * @param force Whether to "force" starting this search. If the search is not forced, the
     *              search may be delayed by a small time, so that network requests are not sent
     *              too often.  If the search is forced, the network request is sent immediately.
     */
    public void startSearch(@Nullable String term, boolean force) {
        if (!force && StringUtils.equals(currentSearchTerm, term)) {
            return;
        }

        cancelSearchTask();
        currentSearchTerm = term;

        if (isBlank(term)) {
            clearResults();
            return;
        }

        List<SearchResult> cacheResult = searchResultsCache.get(getSearchLanguageCode() + "-" + term);
        List<Integer> cacheResultsCount = searchResultsCountCache.get(getSearchLanguageCode() + "-" + term);
        if (cacheResult != null && !cacheResult.isEmpty()) {
            clearResults();
            displayResults(cacheResult);
            return;
        } else if (cacheResultsCount != null && !cacheResultsCount.isEmpty()) {
            clearResults();
            displayResultsCount(cacheResultsCount);
            return;
        }

        doTitlePrefixSearch(term, force);
    }

    public void clearSearchResultsCountCache() {
        searchResultsCountCache.evictAll();
    }

    private void doTitlePrefixSearch(final String searchTerm, boolean force) {
        cancelSearchTask();
        final long startTime = System.nanoTime();
        updateProgressBar(true);

        disposables.add(Observable.timer(force ? 0 : DELAY_MILLIS, TimeUnit.MILLISECONDS).flatMap(timer ->
                Observable.zip(ServiceFactory.get(WikiSite.forLanguageCode(getSearchLanguageCode())).prefixSearch(searchTerm, BATCH_SIZE, searchTerm),
                        (searchTerm.length() >= 2) ? Observable.fromCallable(() -> ReadingListDbHelper.instance().findPageForSearchQueryInAnyList(currentSearchTerm)) : Observable.just(new SearchResults()),
                        (searchTerm.length() >= 2) ? Observable.fromCallable(() -> HistoryDbHelper.INSTANCE.findHistoryItem(currentSearchTerm)) : Observable.just(new SearchResults()),
                        (searchResponse, readingListSearchResults, historySearchResults) -> {

                            SearchResults searchResults;
                            if (searchResponse != null && searchResponse.query() != null && searchResponse.query().pages() != null) {
                                // noinspection ConstantConditions
                                searchResults = new SearchResults(searchResponse.query().pages(),
                                        WikiSite.forLanguageCode(getSearchLanguageCode()), searchResponse.continuation(),
                                        searchResponse.suggestion());
                            } else {
                                // A prefix search query with no results will return the following:
                                //
                                // {
                                //   "batchcomplete": true,
                                //   "query": {
                                //      "search": []
                                //   }
                                // }
                                //
                                // Just return an empty SearchResults() in this case.
                                searchResults = new SearchResults();
                            }
                            handleSuggestion(searchResults.getSuggestion());
                            List<SearchResult> resultList = new ArrayList<>();
                            addSearchResultsFromTabs(resultList);
                            resultList.addAll(readingListSearchResults.getResults());
                            resultList.addAll(historySearchResults.getResults());
                            resultList.addAll(searchResults.getResults());
                            return resultList;
                        }))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doAfterTerminate(() -> updateProgressBar(false))
                .subscribe(results -> {
                    searchErrorView.setVisibility(GONE);
                    handleResults(results, searchTerm, startTime);
                }, caught -> {
                    searchErrorView.setVisibility(VISIBLE);
                    searchErrorView.setError(caught);
                    searchResultsContainer.setVisibility(GONE);
                    logError(false, startTime);
                }));
    }

    private void addSearchResultsFromTabs(List<SearchResult> resultList) {
        if (currentSearchTerm.length() < 2) {
            return;
        }
        List<Tab> tabList = WikipediaApp.getInstance().getTabList();
        for (Tab tab : tabList) {
            if (tab.getBackStackPositionTitle() != null && tab.getBackStackPositionTitle().getDisplayText().toLowerCase().contains(currentSearchTerm.toLowerCase())) {
                SearchResult searchResult = new SearchResult(tab.getBackStackPositionTitle(), SearchResult.SearchResultType.TAB_LIST);
                resultList.add(searchResult);
                return;
            }
        }
    }

    private void handleResults(@NonNull List<SearchResult> resultList, @NonNull String searchTerm, long startTime) {
        // To ease data analysis and better make the funnel track with user behaviour,
        // only transmit search results events if there are a nonzero number of results
        if (!resultList.isEmpty()) {
            clearResults();
            displayResults(resultList);
            log(resultList, startTime);
        }

        // add titles to cache...
        searchResultsCache.put(getSearchLanguageCode() + "-" + searchTerm, resultList);

        // scroll to top, but post it to the message queue, because it should be done
        // after the data set is updated.
        searchResultsList.post(() -> {
            if (!isAdded()) {
                return;
            }
            searchResultsList.scrollToPosition(0);
        });

        if (resultList.isEmpty()) {
            // kick off full text search if we get no results
            doFullTextSearch(currentSearchTerm, null, true);
        }
    }

    private void handleSuggestion(@Nullable String suggestion) {
        if (suggestion != null) {
            searchSuggestion.setText(StringUtil.fromHtml("<u>"
                    + getString(R.string.search_did_you_mean, suggestion)
                    + "</u>"));
            searchSuggestion.setTag(suggestion);
            searchSuggestion.setVisibility(VISIBLE);
        } else {
            searchSuggestion.setVisibility(GONE);
        }
    }

    private void cancelSearchTask() {
        updateProgressBar(false);
        disposables.clear();
    }

    private void doFullTextSearch(final String searchTerm,
                                  final Map<String, String> continueOffset,
                                  final boolean clearOnSuccess) {
        final long startTime = System.nanoTime();
        updateProgressBar(true);

        disposables.add(ServiceFactory.get(WikiSite.forLanguageCode(getSearchLanguageCode())).fullTextSearch(searchTerm, BATCH_SIZE,
                continueOffset != null ? continueOffset.get("continue") : null, continueOffset != null ? continueOffset.get("gsroffset") : null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(response -> {
                    if (response.query() != null) {
                        // noinspection ConstantConditions
                        return new SearchResults(response.query().pages(), WikiSite.forLanguageCode(getSearchLanguageCode()),
                                response.continuation(), null);
                    }
                    // A 'morelike' search query with no results will just return an API warning:
                    //
                    // {
                    //   "batchcomplete": true,
                    //   "warnings": {
                    //      "search": {
                    //        "warnings": "No valid titles provided to 'morelike'."
                    //      }
                    //   }
                    // }
                    //
                    // Just return an empty SearchResults() in this case.
                    return new SearchResults();
                })
                .flatMap(results -> {
                    List<SearchResult> resultList = results.getResults();
                    cache(resultList, searchTerm);
                    log(resultList, startTime);
                    if (clearOnSuccess) {
                        clearResults(false);
                    }
                    searchErrorView.setVisibility(View.GONE);

                    // full text special:
                    SearchResultsFragment.this.lastFullTextResults = results;
                    if (!resultList.isEmpty()) {
                        displayResults(resultList);
                    } else {
                        updateProgressBar(true);
                    }
                    return resultList.isEmpty() ? doSearchResultsCountObservable(searchTerm) : Observable.empty();
                })
                .toList()
                .doAfterTerminate(() -> updateProgressBar(false))
                .subscribe(list -> {
                    if (!list.isEmpty()) {

                        // make a singleton list if all results are empty.
                        int sum = 0;
                        for (int count : list) {
                            sum += count;
                            if (sum > 0) {
                                break;
                            }
                        }

                        if (sum == 0) {
                            list = Collections.singletonList(0);
                        }

                        searchResultsCountCache.put(getSearchLanguageCode() + "-" + searchTerm, list);
                        displayResultsCount(list);
                    }
                }, throwable -> {
                    // If there's an error, just log it and let the existing prefix search results be.
                    logError(true, startTime);
                }));
    }

    private Observable<Integer> doSearchResultsCountObservable(final String searchTerm) {
        return Observable.fromIterable(WikipediaApp.getInstance().language().getAppLanguageCodes())
                .concatMap(langCode -> {
                    if (langCode.equals(getSearchLanguageCode())) {
                        return Observable.just(new MwQueryResponse());
                    }
                    return ServiceFactory.get(WikiSite.forLanguageCode(langCode)).prefixSearch(searchTerm, BATCH_SIZE, searchTerm)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .flatMap(response -> {
                                        if (response.query() != null && response.query().pages() != null) {
                                            return Observable.just(response);
                                        }
                                        return ServiceFactory.get(WikiSite.forLanguageCode(langCode)).fullTextSearch(searchTerm, BATCH_SIZE, null, null);
                                    });
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(response -> response.query() != null && response.query().pages() != null ? response.query().pages().size() : 0);
    }

    private void clearResults() {
        clearResults(true);
    }

    private void updateProgressBar(boolean enabled) {
        Callback callback = callback();
        if (callback != null) {
            callback.onSearchProgressBar(enabled);
        }
    }

    private void clearResults(boolean clearSuggestion) {
        searchResultsContainer.setVisibility(View.GONE);
        searchErrorView.setVisibility(View.GONE);
        searchResultsContainer.setVisibility(GONE);
        searchErrorView.setVisibility(GONE);
        if (clearSuggestion) {
            searchSuggestion.setVisibility(GONE);
        }

        lastFullTextResults = null;

        totalResults.clear();
        resultsCountList.clear();

        getAdapter().notifyDataSetChanged();
    }

    private SearchResultAdapter getAdapter() {
        return (SearchResultAdapter) searchResultsList.getAdapter();
    }

    /**
     * Displays results passed to it as search suggestions.
     *
     * @param results List of results to display. If null, clears the list of suggestions & hides it.
     */
    private void displayResults(List<SearchResult> results) {
        for (SearchResult newResult : results) {
            boolean contains = false;
            for (SearchResult result : totalResults) {
                if (newResult.getPageTitle().equals(result.getPageTitle())) {
                    contains = true;
                    break;
                }
            }
            if (!contains) {
                totalResults.add(newResult);
            }
        }
        searchResultsContainer.setVisibility(View.VISIBLE);
        getAdapter().notifyDataSetChanged();
    }

    private void displayResultsCount(@NonNull List<Integer> list) {
        resultsCountList.clear();
        resultsCountList.addAll(list);
        searchResultsContainer.setVisibility(View.VISIBLE);
        getAdapter().notifyDataSetChanged();
    }

    private class SearchResultsFragmentLongPressHandler implements LongPressMenu.Callback {
        private final int lastPositionRequested;
        private final Callback callback = callback();

        SearchResultsFragmentLongPressHandler(int position) {
            lastPositionRequested = position;
        }

        @Override
        public void onOpenLink(@NonNull HistoryEntry entry) {
            if (callback != null) {
                callback.navigateToTitle(entry.getTitle(), false, lastPositionRequested);
            }
        }

        @Override
        public void onOpenInNewTab(@NonNull HistoryEntry entry) {
            if (callback != null) {
                callback.navigateToTitle(entry.getTitle(), true, lastPositionRequested);
            }
        }

        @Override
        public void onAddRequest(@NonNull HistoryEntry entry, boolean addToDefault) {
            if (callback != null) {
                callback.onSearchAddPageToList(entry, addToDefault);
            }
        }

        @Override
        public void onMoveRequest(@Nullable ReadingListPage page, @NonNull HistoryEntry entry) {
            if (callback != null) {
                callback.onSearchMovePageToList(page.listId(), entry);
            }
        }
    }

    private final class SearchResultAdapter extends RecyclerView.Adapter<DefaultViewHolder<View>> {

        private static final int VIEW_TYPE_ITEM = 0;
        private static final int VIEW_TYPE_NO_RESULTS = 1;

        @Override
        public int getItemViewType(int position) {
            return totalResults.isEmpty() ? VIEW_TYPE_NO_RESULTS : VIEW_TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return totalResults.isEmpty() ? resultsCountList.size() : totalResults.size();
        }

        @Override
        public DefaultViewHolder<View> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_ITEM) {
                return new SearchResultItemViewHolder(LayoutInflater.from(getContext())
                        .inflate(R.layout.item_search_result, parent, false));
            } else {
                return new NoSearchResultItemViewHolder(LayoutInflater.from(getContext())
                        .inflate(R.layout.item_search_no_results, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull DefaultViewHolder<View> holder, int pos) {
            if (holder instanceof SearchResultItemViewHolder) {
                ((SearchResultItemViewHolder) holder).bindItem(pos);
            } else if (holder instanceof NoSearchResultItemViewHolder) {
                ((NoSearchResultItemViewHolder) holder).bindItem(pos);
            }
        }
    }

    private class NoSearchResultItemViewHolder extends DefaultViewHolder<View> {
        NoSearchResultItemViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        private ColorStateList accentColorStateList = ColorStateList.valueOf(ResourceUtil.getThemedColor(requireContext(), R.attr.colorAccent));
        private ColorStateList secondaryColorStateList = ColorStateList.valueOf(ResourceUtil.getThemedColor(requireContext(), R.attr.material_theme_secondary_color));

        void bindItem(int position) {
            String langCode = WikipediaApp.getInstance().language().getAppLanguageCodes().get(position);
            int resultsCount = resultsCountList.get(position);
            TextView resultsText = getView().findViewById(R.id.results_text);
            TextView languageCodeText = getView().findViewById(R.id.language_code);
            resultsText.setText(resultsCount == 0 ? getString(R.string.search_results_count_zero)
                    : getResources().getQuantityString(R.plurals.search_results_count, resultsCount, resultsCount));
            resultsText.setTextColor(resultsCount == 0 ? secondaryColorStateList : accentColorStateList);
            languageCodeText.setVisibility(resultsCountList.size() == 1 ? View.GONE : View.VISIBLE);
            languageCodeText.setText(langCode);
            languageCodeText.setTextColor(resultsCount == 0 ? secondaryColorStateList : accentColorStateList);
            languageCodeText.setBackgroundTintList(resultsCount == 0 ? secondaryColorStateList : accentColorStateList);
            ViewUtil.formatLangButton(languageCodeText, langCode,
                    LANG_BUTTON_TEXT_SIZE_SMALLER, LANG_BUTTON_TEXT_SIZE_LARGER);
            getView().setEnabled(resultsCount > 0);
            getView().setOnClickListener(view -> {
                if (getParentFragment() != null) {
                    ((SearchFragment) getParentFragment()).setUpLanguageScroll(position);
                }
            });
        }
    }

    private class SearchResultItemViewHolder extends DefaultViewHolder<View> {
        SearchResultItemViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        void bindItem(int position) {
            TextView pageTitleText = getView().findViewById(R.id.page_list_item_title);
            SearchResult result = totalResults.get(position);

            ImageView searchResultItemImage = getView().findViewById(R.id.page_list_item_image);
            ImageView searchResultIcon = getView().findViewById(R.id.page_list_icon);
            GoneIfEmptyTextView descriptionText = getView().findViewById(R.id.page_list_item_description);
            TextView redirectText = getView().findViewById(R.id.page_list_item_redirect);
            View redirectArrow = getView().findViewById(R.id.page_list_item_redirect_arrow);
            if (TextUtils.isEmpty(result.getRedirectFrom())) {
                redirectText.setVisibility(GONE);
                redirectArrow.setVisibility(GONE);
                descriptionText.setText(result.getPageTitle().getDescription());
            } else {
                redirectText.setVisibility(VISIBLE);
                redirectArrow.setVisibility(VISIBLE);
                redirectText.setText(getString(R.string.search_redirect_from, result.getRedirectFrom()));
                descriptionText.setVisibility(GONE);
            }
            if (result.getType() == SearchResult.SearchResultType.SEARCH) {
                searchResultIcon.setVisibility(GONE);
            } else {

                searchResultIcon.setVisibility(VISIBLE);
                searchResultIcon.setImageResource(result.getType() == SearchResult.SearchResultType.HISTORY
                        ? R.drawable.ic_history_24 : result.getType() == SearchResult.SearchResultType.TAB_LIST
                        ? R.drawable.ic_tab_one_24px : R.drawable.ic_bookmark_white_24dp);
            }

            // highlight search term within the text
            StringUtil.boldenKeywordText(pageTitleText, result.getPageTitle().getDisplayText(), currentSearchTerm);

            searchResultItemImage.setVisibility((result.getPageTitle().getThumbUrl() == null)
                    ? result.getType() == SearchResult.SearchResultType.SEARCH ? GONE : INVISIBLE : VISIBLE);
            ViewUtil.loadImageWithRoundedCorners(searchResultItemImage, result.getPageTitle().getThumbUrl());

            // ...and lastly, if we've scrolled to the last item in the list, then
            // continue searching!
            if (position == (totalResults.size() - 1) && WikipediaApp.getInstance().isOnline()) {
                if (lastFullTextResults == null) {
                    // the first full text search
                    doFullTextSearch(currentSearchTerm, null, false);
                } else if (lastFullTextResults.getContinuation() != null && !lastFullTextResults.getContinuation().isEmpty()) {
                    // subsequent full text searches
                    doFullTextSearch(currentSearchTerm, lastFullTextResults.getContinuation(), false);
                }
            }

            getView().setLongClickable(true);
            getView().setOnClickListener(view -> {
                Callback callback = callback();
                if (callback != null && position < totalResults.size()) {
                    callback.navigateToTitle(totalResults.get(position).getPageTitle(), false, position);
                }
            });
            getView().setOnCreateContextMenuListener(new LongPressHandler(getView(),
                    result.getPageTitle(), HistoryEntry.SOURCE_SEARCH, new SearchResultsFragmentLongPressHandler(position)));
        }
    }

    private void cache(@NonNull List<SearchResult> resultList, @NonNull String searchTerm) {
        String cacheKey = getSearchLanguageCode() + "-" + searchTerm;
        List<SearchResult> cachedTitles = searchResultsCache.get(cacheKey);
        if (cachedTitles != null) {
            cachedTitles.addAll(resultList);
            searchResultsCache.put(cacheKey, cachedTitles);
        }
    }

    private void log(@NonNull List<SearchResult> resultList, long startTime) {
        // To ease data analysis and better make the funnel track with user behaviour,
        // only transmit search results events if there are a nonzero number of results
        if (callback() != null && !resultList.isEmpty()) {
            // noinspection ConstantConditions
            callback().getFunnel().searchResults(true, resultList.size(), displayTime(startTime), getSearchLanguageCode());
        }
    }

    private void logError(boolean fullText, long startTime) {
        if (callback() != null) {
            // noinspection ConstantConditions
            callback().getFunnel().searchError(fullText, displayTime(startTime), getSearchLanguageCode());
        }
    }

    private int displayTime(long startTime) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    }

    @Nullable
    private Callback callback() {
        return FragmentUtil.getCallback(this, Callback.class);
    }

    private String getSearchLanguageCode() {
        return ((SearchFragment) getParentFragment()).getSearchLanguageCode();
    }
}

