package com.rcs.trie

import com.rcs.trie.FuzzySubstringMatchingStrategy.*

/**
 * Invariable properties of the search request - these never change.
 */
private data class SearchRequest(
    val matchingStrategy: FuzzySubstringMatchingStrategy,
    val search: String,
    val numberOfPredeterminedErrors: Int,
    val errorTolerance: Int,
)

/**
 * Variable properties that change depending on what TrieNode we're looking at.
 */
private data class SearchVariables<T>(
    val node: TrieNode<T>,
    val sequence: String,
    val isGatherState: Boolean,
)

/**
 * The search coordinates, which may or may not lead to a successful match.
 */
private data class SearchCoordinates(
    val searchIndex: Int,
    val numberOfMatches: Int,
    val numberOfErrors: Int,
    val startMatchIndex: Int?,
    val endMatchIndex: Int?,
    val swapChar: List<SwapChars>?
)

/**
 * Holds swap characters for matching strategy TYPO and SWAP
 */
private data class SwapChars(
    val fromSource: String,
    val fromTarget: String
)

/**
 * A convenience class for passing around new search error states.
 */
private data class ErrorStrategy<T>(
    val node: TrieNode<T>,
    val searchIndex: Int,
    val sequence: String,
    val swapChar: SwapChars?,
    val startMatchIndex: Int?
)

private val wordSeparatorRegex = "[\\s\\p{P}]".toRegex()

class FuzzySubstringSearchState<T> private constructor(
    private val searchRequest: SearchRequest,
    private val searchVariables: SearchVariables<T>,
    private val searchCoordinates: SearchCoordinates
) {

    fun nextStates(): Collection<FuzzySubstringSearchState<T>> {
        synchronized(searchVariables.node.next) {
            return searchVariables.node.next
                .map { nextStates(it) ?: listOf() }
                .flatten()
        }
    }

    fun hasSearchResult(): Boolean {
        return searchVariables.node.completes() && matches()
    }

    fun buildSearchResult(): TrieSearchResult<T> {
        assert(hasSearchResult())

        val actualErrors = getNumberOfErrorsIncludingMissingCharacters() +
                searchRequest.numberOfPredeterminedErrors

        val assertedStartMatchIndex = searchCoordinates.startMatchIndex!!

        val assertedEndMatchIndex = searchCoordinates.endMatchIndex!!

        val matchedWholeSequence = actualErrors == 0
                && matchedWholeSequence(assertedStartMatchIndex, assertedEndMatchIndex)

        val matchedWholeWord = actualErrors == 0
                && matchedWholeWord(assertedStartMatchIndex, assertedEndMatchIndex)

        val indexOfWordSeparatorBefore = searchVariables.sequence
            .indexOfLastWordSeparator(assertedStartMatchIndex) ?: -1

        val indexOfWordSeparatorAfter = searchVariables.sequence
            .indexOfFirstWordSeparator(assertedEndMatchIndex + 1) ?: searchVariables.sequence.length

        val prefixDistance = assertedStartMatchIndex - indexOfWordSeparatorBefore - 1

        val matchedSubstring = searchVariables.sequence
            .substring(assertedStartMatchIndex, assertedEndMatchIndex + 1)

        val matchedWord = searchVariables.sequence.substring(indexOfWordSeparatorBefore + 1, indexOfWordSeparatorAfter)

        return TrieSearchResult(
            searchVariables.sequence,
            searchVariables.node.value!!,
            matchedSubstring,
            matchedWord,
            searchCoordinates.numberOfMatches,
            actualErrors,
            prefixDistance,
            matchedWholeSequence,
            matchedWholeWord
        )
    }

    private fun matches(): Boolean {
        return searchCoordinates.startMatchIndex != null
                && searchCoordinates.endMatchIndex != null
                && hasMinimumNumberOfMatches()
                && getNumberOfErrorsIncludingMissingCharacters() <= searchRequest.errorTolerance
                && (searchCoordinates.swapChar ?: emptyList()).isEmpty()
    }

    private fun hasMinimumNumberOfMatches(): Boolean {
        val minimumRequiredMatches = searchRequest.search.length - searchRequest.errorTolerance
        return searchCoordinates.numberOfMatches >= minimumRequiredMatches
    }

    private fun nextStates(nextNode: TrieNode<T>): Collection<FuzzySubstringSearchState<T>>? {
        if (shouldCull(nextNode)) {
            return null
        }

        return buildMatchState(nextNode)
            ?: buildErrorState(nextNode)
            ?: buildResetState(nextNode)
            ?: buildGatherState(nextNode)
    }

    private fun shouldCull(nextNode: TrieNode<T>): Boolean {
        val numberOfMatchingCharactersNeeded = searchRequest.search.length -
                searchCoordinates.numberOfMatches -
                searchRequest.errorTolerance

        return nextNode.depth < numberOfMatchingCharactersNeeded
    }

    private fun buildMatchState(nextNode: TrieNode<T>): Collection<FuzzySubstringSearchState<T>>? {
        if (searchVariables.isGatherState) {
            return null
        }

        return when {
            nextNodeMatches(nextNode) ->
                listOf(
                    FuzzySubstringSearchState(
                        searchRequest = searchRequest,
                        searchVariables = SearchVariables(
                            node = nextNode,
                            sequence = searchVariables.sequence + nextNode.string,
                            isGatherState = false
                        ),
                        searchCoordinates = SearchCoordinates(
                            startMatchIndex = searchCoordinates.startMatchIndex ?: searchVariables.sequence.length,
                            endMatchIndex = searchVariables.sequence.length,
                            searchIndex = searchCoordinates.searchIndex + 1,
                            numberOfMatches = searchCoordinates.numberOfMatches + 1,
                            numberOfErrors = searchCoordinates.numberOfErrors,
                            swapChar = searchCoordinates.swapChar
                        )
                    )
                )
            else ->
                null
        }
    }

    private fun nextNodeMatches(nextNode: TrieNode<T>): Boolean {
        if (searchRequest.matchingStrategy == WILDCARD
            && searchRequest.search[searchCoordinates.searchIndex] == '*') {
            return true
        }

        val wasMatchingBefore = searchCoordinates.numberOfMatches > 0

        val matchingPreconditions = when (searchRequest.matchingStrategy) {
            FUZZY_PREFIX ->
                wasMatchingBefore || distanceToStartWordSeparatorIsPermissible()
            EXACT_PREFIX, FUZZY_POSTFIX ->
                wasMatchingBefore || searchVariables.node.string.isWordSeparator()
            else ->
                true
        }

        return matchingPreconditions && searchCoordinatesMatch(nextNode)
    }

    private fun buildErrorState(nextNode: TrieNode<T>): Collection<FuzzySubstringSearchState<T>>? {
        if (searchVariables.isGatherState) {
            return null
        }

        val swapSatisfied = searchCoordinates.swapChar.getMatching(nextNode)

        return when {
            swapSatisfied != null -> {
                listOf(
                    FuzzySubstringSearchState(
                        searchRequest = searchRequest,
                        searchVariables = SearchVariables(
                            node = nextNode,
                            sequence = searchVariables.sequence + nextNode.string,
                            isGatherState = false
                        ),
                        searchCoordinates = SearchCoordinates(
                            startMatchIndex = searchCoordinates.startMatchIndex ?: searchVariables.sequence.length,
                            endMatchIndex = searchVariables.sequence.length,
                            searchIndex = searchCoordinates.searchIndex + 1,
                            numberOfMatches = searchCoordinates.numberOfMatches,
                            numberOfErrors = searchCoordinates.numberOfErrors + 1,
                            swapChar = searchCoordinates.swapChar!!.filter { sc -> sc != swapSatisfied }
                        )
                    )
                )
            }
            shouldProduceErrorStates() ->
                getErrorStrategies(nextNode).map {
                    FuzzySubstringSearchState(
                        searchRequest = searchRequest,
                        searchVariables = SearchVariables(
                            node = it.node,
                            sequence = it.sequence,
                            isGatherState = false
                        ),
                        searchCoordinates = SearchCoordinates(
                            startMatchIndex = it.startMatchIndex,
                            endMatchIndex = searchCoordinates.endMatchIndex,
                            searchIndex = it.searchIndex,
                            numberOfMatches = searchCoordinates.numberOfMatches,
                            numberOfErrors = searchCoordinates.numberOfErrors + 1,
                            swapChar = it.swapChar?.let { swapChar ->
                                (searchCoordinates.swapChar ?: mutableListOf()).plus(swapChar).toMutableList()
                            }
                        )
                    )
                }
            else ->
                null
        }
    }

    private fun shouldProduceErrorStates(): Boolean {
        val isNotInMiddleOfSwap = searchCoordinates.swapChar?.isEmpty() ?: true
        val wasMatchingBefore = searchCoordinates.numberOfMatches > 0
        val hasSearchCharacters = searchCoordinates.searchIndex + 1 < searchRequest.search.length
        val hasErrorAllowance = searchCoordinates.numberOfErrors < searchRequest.errorTolerance

        return hasSearchCharacters
                && hasErrorAllowance
                && when (searchRequest.matchingStrategy) {
                    SWAP ->
                        true
                    TYPO ->
                        isNotInMiddleOfSwap
                    FUZZY_POSTFIX ->
                        hasMinimumNumberOfMatches()
                    FUZZY_PREFIX ->
                        wasMatchingBefore || distanceToStartWordSeparatorIsPermissible()
                    else ->
                        wasMatchingBefore
                }
    }

    private fun getErrorStrategies(nextNode: TrieNode<T>): List<ErrorStrategy<T>> {
        // 1. typo/swap: increment searchIndex and go to the next node, and keep track of swap letters
        val typoSwapStrategy = ErrorStrategy(
            nextNode,
            searchCoordinates.searchIndex + 1,
            searchVariables.sequence + nextNode.string,
            SwapChars(searchRequest.search[searchCoordinates.searchIndex].toString(), nextNode.string),
            searchCoordinates.startMatchIndex ?: searchVariables.sequence.length
        )

        if (searchRequest.matchingStrategy == TYPO
                || searchRequest.matchingStrategy == SWAP) {
            return listOf(typoSwapStrategy)
        }

        // 2. misspelling: increment searchIndex and go to the next node
        val misspellingStrategy = ErrorStrategy(
            nextNode,
            searchCoordinates.searchIndex + 1,
            searchVariables.sequence + nextNode.string,
            null,
            searchCoordinates.startMatchIndex
        )

        // 3. missing letter in data: increment searchIndex and stay at the previous node
        val missingTargetLetterStrategy = ErrorStrategy(
            searchVariables.node,
            searchCoordinates.searchIndex + 1,
            searchVariables.sequence,
            null,
            searchCoordinates.startMatchIndex
        )

        // 4. missing letter in search keyword: keep searchIndex the same and go to the next node
        val missingSourceLetterStrategy = ErrorStrategy(
            nextNode,
            searchCoordinates.searchIndex,
            searchVariables.sequence + nextNode.string,
            null,
            searchCoordinates.startMatchIndex
        )

        return listOf(misspellingStrategy, missingTargetLetterStrategy, missingSourceLetterStrategy)
    }

    private fun buildResetState(
        nextNode: TrieNode<T>,
        forceReturn: Boolean = false
    ): Collection<FuzzySubstringSearchState<T>>? {

        return when {
            !forceReturn && (searchVariables.isGatherState || matches()) ->
                null
            else ->
                listOf(
                    FuzzySubstringSearchState(
                        searchRequest = searchRequest,
                        searchVariables = SearchVariables(
                            node = nextNode,
                            sequence = searchVariables.sequence + nextNode.string,
                            isGatherState = false
                        ),
                        searchCoordinates = SearchCoordinates(
                            startMatchIndex = null,
                            endMatchIndex = null,
                            searchIndex = 0,
                            numberOfMatches = 0,
                            numberOfErrors = 0,
                            swapChar = null
                        )
                    )
                )
        }
    }

    private fun buildGatherState(nextNode: TrieNode<T>): List<FuzzySubstringSearchState<T>> {
        val gatherStates = mutableListOf<FuzzySubstringSearchState<T>>()

        val defaultGatherState = FuzzySubstringSearchState(
            searchRequest = searchRequest,
            searchVariables = SearchVariables(
                node = nextNode,
                sequence = searchVariables.sequence + nextNode.string,
                isGatherState = true,
            ),
            searchCoordinates = searchCoordinates
        )

        gatherStates.add(defaultGatherState)

        // we spin off a new reset state, in case we find a better match further in the string.
        // we must only do this once, if this is the first time this state enters into the gather state
        val perfectMatch = searchRequest.search.length == searchCoordinates.numberOfMatches
        if (!searchVariables.isGatherState && !perfectMatch) {
            gatherStates.addAll(buildResetState(nextNode, true)!!)
        }

        return gatherStates
    }

    private fun searchCoordinatesMatch(nextNode: TrieNode<T>): Boolean {
        return searchCoordinates.searchIndex < searchRequest.search.length
                && nextNode.string == searchRequest.search[searchCoordinates.searchIndex].toString()
    }

    private fun getNumberOfErrorsIncludingMissingCharacters(): Int {
        val unmatchedCharacters = searchRequest.search.length - searchCoordinates.searchIndex
        if (unmatchedCharacters < 0) {
            throw AssertionError("Number of unmatched characters should never be negative")
        }
        return searchCoordinates.numberOfErrors +
                unmatchedCharacters
    }

    private fun distanceToStartWordSeparatorIsPermissible(): Boolean {
        val indexOfLastWordSeparator = searchVariables.sequence.indexOfLastWordSeparator() ?: -1
        val distanceToWordSeparator = searchVariables.sequence.length - indexOfLastWordSeparator - 2
        return distanceToWordSeparator <= searchCoordinates.numberOfErrors
    }

    private fun matchedWholeSequence(startMatchIndex: Int, endMatchIndex: Int): Boolean {
        return startMatchIndex == 0 && endMatchIndex >= searchVariables.sequence.length - 1
    }

    private fun matchedWholeWord(startMatchIndex: Int, endMatchIndex: Int): Boolean {
        return searchVariables.sequence.isWordSeparatorAt(startMatchIndex - 1)
                && searchVariables.sequence.isWordSeparatorAt(endMatchIndex + 1)
    }

    private fun String.isWordSeparatorAt(index: Int): Boolean {
        return index < 0 || index >= this.length || this[index].toString().isWordSeparator()
    }

    private fun String.isWordSeparator(): Boolean {
        return this == "" /* == root */ || this.matches(wordSeparatorRegex)
    }

    private fun CharSequence.indexOfLastWordSeparator(endIndex: Int = this.length - 1): Int? {
        return (0..endIndex).reversed().firstOrNull {
            this[it].toString().matches(wordSeparatorRegex)
        }
    }

    private fun CharSequence.indexOfFirstWordSeparator(startIndex: Int = 0): Int? {
        return (startIndex..<this.length).firstOrNull {
            this[it].toString().matches(wordSeparatorRegex)
        }
    }

    private fun List<SwapChars>?.getMatching(node: TrieNode<T>): SwapChars? {
        return this?.firstOrNull {
            it.fromSource == node.string
                    && it.fromTarget == searchRequest.search[searchCoordinates.searchIndex].toString()
        }
    }

    companion object {

        /**
         * Emulates a public constructor, keeping some properties private as part of
         * this class's implementation details
         */
        operator fun <T> invoke(
            root: TrieNode<T>,
            search: String,
            numberOfPredeterminedErrors: Int,
            errorTolerance: Int,
            matchingStrategy: FuzzySubstringMatchingStrategy
        ): FuzzySubstringSearchState<T> {

            // this class only work works when beginning with the root node
            if (!root.isRoot()) {
                throw IllegalArgumentException("Node must be root")
            }

            return FuzzySubstringSearchState(
                searchRequest = SearchRequest(
                    matchingStrategy = matchingStrategy,
                    search = search,
                    numberOfPredeterminedErrors = numberOfPredeterminedErrors,
                    errorTolerance = errorTolerance,
                ),
                searchVariables = SearchVariables(
                    node = root,
                    sequence = "",
                    isGatherState = false,
                ),
                searchCoordinates = SearchCoordinates(0, 0, 0, null, null, null)
            )
        }
    }
}