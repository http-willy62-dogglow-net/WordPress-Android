package org.wordpress.android.ui.stats.refresh.lists.sections

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.store.StatsStore.StatsTypes
import org.wordpress.android.ui.stats.refresh.lists.NavigationTarget
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.State.Data
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.State.Empty
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.State.Loading
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.StatelessUseCase.NotUsedUiState
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.UseCaseModel.UseCaseState.EMPTY
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.UseCaseModel.UseCaseState.ERROR
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.UseCaseModel.UseCaseState.LOADING
import org.wordpress.android.ui.stats.refresh.lists.sections.BaseStatsUseCase.UseCaseModel.UseCaseState.SUCCESS
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.merge

/**
 * Do not override this class directly. Use StatefulUseCase or StatelessUseCase instead.
 */
abstract class BaseStatsUseCase<DOMAIN_MODEL, UI_STATE>(
    val type: StatsTypes,
    private val mainDispatcher: CoroutineDispatcher,
    private val defaultUiState: UI_STATE
) {
    private val domainModel = MutableLiveData<State<DOMAIN_MODEL>>()
    private val uiState = MutableLiveData<UI_STATE>()
    val liveData: LiveData<UseCaseModel> = merge(domainModel, uiState) { data, uiState ->
        try {
            val previousModel = value() ?: UseCaseModel(type)
            when (data) {
                is State.Loading -> {
                    previousModel.copy(state = LOADING, stateData = buildLoadingItem())
                }
                is State.Error -> {
                    previousModel.copy(state = ERROR, stateData = buildErrorItem())
                }
                is Data -> {
                    UseCaseModel(type, data = buildUiModel(data.model, uiState ?: defaultUiState))
                }
                is Empty, null -> UseCaseModel(type, state = EMPTY, stateData = buildEmptyItem())
            }
        } catch (e: Exception) {
            AppLog.e(AppLog.T.STATS, e)
            UseCaseModel(type, state = ERROR, stateData = buildErrorItem())
        }
    }

    private fun value() = liveData.value

    private val mutableNavigationTarget = MutableLiveData<NavigationTarget>()
    val navigationTarget: LiveData<NavigationTarget> = mutableNavigationTarget

    /**
     * Fetches data either from a local cache or from remote API
     * @param site for which we're fetching the data
     * @param refresh is true when we want to get the remote data
     * @param forced is true when we want to get fresh data and skip the cache
     */
    suspend fun fetch(site: SiteModel, refresh: Boolean, forced: Boolean) {
        val firstLoad = domainModel.value == null
        var emptyDb = false
        if (firstLoad || domainModel.value is Loading) {
            val cachedData = loadCachedData(site)
            withContext(mainDispatcher) {
                if (cachedData != null) {
                    domainModel.value = Data(model = cachedData)
                } else {
                    emptyDb = true
                }
            }
        }
        if (firstLoad || refresh || domainModel.value !is Data || emptyDb) {
            if (domainModel.value == null) {
                withContext(mainDispatcher) {
                    domainModel.value = State.Loading()
                }
            }
            val state = fetchRemoteData(site, forced)
            withContext(mainDispatcher) {
                domainModel.value = state
            }
        }
    }

    /**
     * Trigger this method when the UI state has changed.
     * @param newState
     */
    fun onUiState(newState: UI_STATE?) {
        uiState.value = newState ?: uiState.value
    }

    /**
     * Trigger this method when updating only a part of UI state.
     * @param update function
     */
    fun updateUiState(update: (UI_STATE) -> UI_STATE) {
        val previousState = uiState.value ?: defaultUiState
        val updatedState = update(previousState)
        if (previousState != updatedState) {
            uiState.value = updatedState
        }
    }

    /**
     * Clears the LiveData value when we switch the current Site so we don't show the old data for a new site
     */
    fun clear() {
        domainModel.postValue(null)
        uiState.postValue(null)
    }

    /**
     * Passes a navigation target to the View layer which uses the context to open the correct activity.
     */
    fun navigateTo(target: NavigationTarget) {
        mutableNavigationTarget.value = target
    }

    /**
     * Loads data from a local cache. Returns a null value when the cache is empty.
     * @param site for which we load the data
     */
    protected abstract suspend fun loadCachedData(site: SiteModel): DOMAIN_MODEL?

    /**
     * Fetches remote data from the endpoint.
     * @param site for which we fetch the data
     * @param forced is true when we want to get the fresh data
     */
    protected abstract suspend fun fetchRemoteData(site: SiteModel, forced: Boolean): State<DOMAIN_MODEL>

    /**
     * Transforms given domain model and ui state into the UI model
     * @param domainModel domain model coming from FluxC
     * @param uiState contains UI specific data
     * @return a list of block list data
     */
    protected abstract fun buildUiModel(domainModel: DOMAIN_MODEL, uiState: UI_STATE): List<BlockListItem>

    protected abstract fun buildLoadingItem(): List<BlockListItem>

    protected open fun buildErrorItem(): List<BlockListItem> {
        return buildLoadingItem() + listOf(BlockListItem.Text(textResource = R.string.stats_loading_block_error))
    }

    protected open fun buildEmptyItem(): List<BlockListItem> {
        return buildLoadingItem() + listOf(BlockListItem.Empty(textResource = R.string.stats_no_data_yet))
    }

    sealed class State<DOMAIN_MODEL> {
        data class Error<DOMAIN_MODEL>(val error: String) : State<DOMAIN_MODEL>()
        data class Data<DOMAIN_MODEL>(val model: DOMAIN_MODEL) : State<DOMAIN_MODEL>()
        class Empty<DOMAIN_MODEL> : State<DOMAIN_MODEL>()
        class Loading<DOMAIN_MODEL> : State<DOMAIN_MODEL>()
    }

    data class UseCaseModel(
        val type: StatsTypes,
        val data: List<BlockListItem>? = null,
        val stateData: List<BlockListItem>? = null,
        val state: UseCaseState = SUCCESS
    ) {
        fun copyWithLoadingState(loading: List<BlockListItem>): UseCaseModel {
            return this.copy(state = LOADING, stateData = loading)
        }

        fun copyWithErrorState(error: List<BlockListItem>): UseCaseModel {
            return this.copy(state = ERROR, stateData = error)
        }

        fun copyWithEmptyState(empty: List<BlockListItem>): UseCaseModel {
            return this.copy(state = EMPTY, stateData = empty)
        }

        enum class UseCaseState {
            SUCCESS, ERROR, LOADING, EMPTY
        }
    }

    /**
     * Stateful use case should be used when we have a block that has a UI state that needs to be preserved
     * over rotation pull to refresh. It is for example a block with Tabs or with expandable item.
     * @param defaultUiState default value the UI state should have when the screen first loads
     */
    abstract class StatefulUseCase<DOMAIN_MODEL, UI_STATE>(
        type: StatsTypes,
        mainDispatcher: CoroutineDispatcher,
        private val defaultUiState: UI_STATE
    ) : BaseStatsUseCase<DOMAIN_MODEL, UI_STATE>(type, mainDispatcher, defaultUiState) {
        final override fun buildUiModel(domainModel: DOMAIN_MODEL, uiState: UI_STATE): List<BlockListItem> {
            return buildStatefulUiModel(domainModel, uiState)
        }

        /**
         * Transforms given domain model and ui state into the UI model
         * @param domainModel domain model coming from FluxC
         * @param uiState contains UI specific data
         * @return a list of block list data
         */
        protected abstract fun buildStatefulUiModel(domainModel: DOMAIN_MODEL, uiState: UI_STATE): List<BlockListItem>
    }

    /**
     * Stateless use case should be used for the blocks that display just plain data.
     * These blocks don't have only one UI state and it doesn't change.
     */
    abstract class StatelessUseCase<DOMAIN_MODEL>(
        type: StatsTypes,
        mainDispatcher: CoroutineDispatcher
    ) : BaseStatsUseCase<DOMAIN_MODEL, NotUsedUiState>(type, mainDispatcher, NotUsedUiState) {
        /**
         * Transforms given domain model into the UI model
         * @param domainModel domain model coming from FluxC
         * @return a list of block list data
         */
        abstract fun buildUiModel(domainModel: DOMAIN_MODEL): List<BlockListItem>

        final override fun buildUiModel(
            domainModel: DOMAIN_MODEL,
            uiState: NotUsedUiState
        ): List<BlockListItem> {
            return buildUiModel(domainModel)
        }

        object NotUsedUiState
    }
}
