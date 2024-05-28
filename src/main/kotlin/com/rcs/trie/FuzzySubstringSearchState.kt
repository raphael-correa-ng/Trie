package com.rcs.trie

data class FuzzySubstringSearchState<T>(
    val search: String,
    val node: TrieNode<T>,
    val startMatchIndex: Int?,
    val endMatchIndex: Int?,
    val searchIndex: Int,
    val numberOfMatches: Int,
    val numberOfErrors: Int,
    val errorTolerance: Int,
    val sequence: StringBuilder,
) {
    private val wholeWordSeparator = "[\\s\\p{P}]".toRegex()

    fun sufficientlyMatches(): Boolean {
        return startMatchIndex != null
                && (node.completes() || searchIndex > search.length - 1)
                && numberOfMatches >= search.length - errorTolerance
                && getNumberOfErrorsIncludingMissingLetters() <= errorTolerance
    }

    fun nextBuildState(nextNode: TrieNode<T>): FuzzySubstringSearchState<T> {
        val matchHasEnded = endMatchIndex != null

        if (matchHasEnded) {
            return FuzzySubstringSearchState(
                search = search,
                node = nextNode,
                startMatchIndex = startMatchIndex,
                endMatchIndex = endMatchIndex,
                searchIndex = searchIndex,
                numberOfMatches = numberOfMatches,
                numberOfErrors = numberOfErrors,
                errorTolerance = errorTolerance,
                sequence = StringBuilder(sequence).append(nextNode.string)
            )
        }

        val nextNodeMatches = searchIndex < search.length
                && nextNode.string == search[searchIndex].toString()

        if (nextNodeMatches) {
            return FuzzySubstringSearchState(
                search = search,
                node = nextNode,
                startMatchIndex = startMatchIndex,
                endMatchIndex = null,
                searchIndex = searchIndex + 1,
                numberOfMatches = numberOfMatches + 1,
                numberOfErrors = numberOfErrors,
                errorTolerance = errorTolerance,
                sequence = StringBuilder(sequence).append(nextNode.string)
            )
        }

        return FuzzySubstringSearchState(
            search = search,
            node = nextNode,
            startMatchIndex = startMatchIndex,
            endMatchIndex = sequence.length - 1,
            searchIndex = searchIndex,
            numberOfMatches = numberOfMatches,
            numberOfErrors = numberOfErrors,
            errorTolerance = errorTolerance,
            sequence = StringBuilder(sequence).append(nextNode.string)
        )
    }

    fun nextSearchStates(
        nextNode: TrieNode<T>,
        matchingStrategy: FuzzySubstringMatchingStrategy
    ): Collection<FuzzySubstringSearchState<T>> {

        val wasMatchingBefore = numberOfMatches > 0

        val matchingPreconditions = when (matchingStrategy) {
            FuzzySubstringMatchingStrategy.LIBERAL ->
                true
            FuzzySubstringMatchingStrategy.MATCH_PREFIX ->
                wasMatchingBefore || node.string == "" || node.string.matches(wholeWordSeparator)
            else ->
                true
        }

        val nextNodeMatches = matchingPreconditions
                && nextNode.string == search[searchIndex].toString()

        // happy path - continue matching
        if (nextNodeMatches) {
            return listOf(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    startMatchIndex = startMatchIndex ?: sequence.length,
                    endMatchIndex = null,
                    searchIndex = searchIndex + 1,
                    numberOfMatches = numberOfMatches + 1,
                    numberOfErrors = numberOfErrors,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )
        }

        // was matching before, but no longer matches - however, there's some error tolerance to be used
        // there are three ways this can go: 1. misspelling, 2. missing letter in search input 3. missing letter in data
        if (wasMatchingBefore && numberOfErrors < errorTolerance) {
            val nextStates = mutableListOf<FuzzySubstringSearchState<T>>()

            // 1. misspelling
            // increment searchIndex and go to the next node
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    startMatchIndex = startMatchIndex,
                    endMatchIndex = null,
                    searchIndex = searchIndex + 1,
                    numberOfMatches = numberOfMatches,
                    numberOfErrors = numberOfErrors + 1,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )

            // 2. missing letter in target data
            // increment searchIndex and stay at the previous node
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = node,
                    startMatchIndex = startMatchIndex,
                    endMatchIndex = null,
                    searchIndex = searchIndex + 1,
                    numberOfMatches = numberOfMatches,
                    numberOfErrors = numberOfErrors + 1,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence)
                )
            )

            // 2. missing letter in search input
            // keep searchIndex the same and go to the next node
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    startMatchIndex = startMatchIndex,
                    endMatchIndex = null,
                    searchIndex = searchIndex,
                    numberOfMatches = numberOfMatches,
                    numberOfErrors = numberOfErrors + 1,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )

            return nextStates
        }

        val nextStates = mutableListOf<FuzzySubstringSearchState<T>>()

        // exhausted all attempts; reset matching
        nextStates.add(
            FuzzySubstringSearchState(
                search = search,
                node = nextNode,
                startMatchIndex = null,
                endMatchIndex = null,
                searchIndex = 0,
                numberOfMatches = 0,
                numberOfErrors = 0,
                errorTolerance = errorTolerance,
                sequence = StringBuilder(sequence).append(nextNode.string)
            )
        )

        // case when the target data might be a match, but there are wrong letters in the beginning
        val shouldConsiderMatchesWithWrongBeginning = when(matchingStrategy) {
            FuzzySubstringMatchingStrategy.ANCHOR_TO_PREFIX ->
                distanceToStartWordSeparatorIsPermissible()
            else ->
                false
        }

        if (shouldConsiderMatchesWithWrongBeginning) {
            for (i in 1..errorTolerance - numberOfErrors) {
                nextStates.add(
                    FuzzySubstringSearchState(
                        search = search,
                        node = nextNode,
                        startMatchIndex = null,
                        endMatchIndex = null,
                        searchIndex = i,
                        numberOfMatches = 0,
                        numberOfErrors = numberOfErrors + i,
                        errorTolerance = errorTolerance,
                        sequence = StringBuilder(sequence).append(nextNode.string)
                    )
                )
            }
        }

        return nextStates
    }

    fun buildSearchResult(matchingStrategy: FuzzySubstringMatchingStrategy): TrieSearchResult<T> {
        val actualErrors = getNumberOfErrorsIncludingMissingLetters()

        val actualEndMatchIndex = endMatchIndex ?: (sequence.length - 1)

        val matchedWholeSequence = actualErrors == 0
                && matchedWholeSequence(startMatchIndex!!, actualEndMatchIndex)

        val matchedWholeWord = actualErrors == 0
                && matchedWholeWord(startMatchIndex!!, actualEndMatchIndex)

        val indexOfWordSeparatorBefore = sequence.subSequence(0, startMatchIndex!! + 1)
            .indexOfLastWordSeparator()

        val indexOfWordSeparatorAfter = sequence.subSequence(startMatchIndex, sequence.length)
            .indexOfFirstWordSeparator()

        val prefixDistance = when {
            indexOfWordSeparatorBefore == -1 -> startMatchIndex
            else -> startMatchIndex - indexOfWordSeparatorBefore - 1
        }

        val wordLength = when {
            indexOfWordSeparatorAfter == -1 -> sequence.length - startMatchIndex + prefixDistance
            else -> indexOfWordSeparatorAfter + prefixDistance
        }

        val matchedSubstring = when(matchingStrategy) {
            FuzzySubstringMatchingStrategy.ANCHOR_TO_PREFIX ->
                sequence.substring(indexOfWordSeparatorBefore + 1, actualEndMatchIndex + 1)
            else ->
                sequence.substring(startMatchIndex, actualEndMatchIndex + 1)
        }

        return TrieSearchResult(
            sequence.toString(),
            node.value!!,
            matchedSubstring,
            numberOfMatches,
            actualErrors,
            prefixDistance,
            wordLength,
            matchedWholeSequence,
            matchedWholeWord
        )
    }

    // this needs to be revised and commented
    // it's a mess, but it does work
    private fun getNumberOfErrorsIncludingMissingLetters(): Int {
        return if (node.completes()) {
            if (numberOfMatches + numberOfErrors < search.length) {
                search.length - numberOfMatches
            } else if (searchIndex < search.length) {
                val additionalWrongLetters = search.length - numberOfMatches
                search.length - numberOfMatches + additionalWrongLetters
            } else {
                numberOfErrors
            }
        } else {
            numberOfErrors
        }
    }

    private fun distanceToStartWordSeparatorIsPermissible(): Boolean {
        val indexOfLastWordSeparator = sequence.indexOfLastWordSeparator()
        val distanceToWordSeparator = sequence.length - 1 - indexOfLastWordSeparator
        return distanceToWordSeparator - 1 <= numberOfErrors
    }

    private fun matchedWholeSequence(startMatchIndex: Int, endMatchIndex: Int): Boolean {
        return startMatchIndex == 0 && endMatchIndex >= sequence.length - 1
    }

    private fun matchedWholeWord(startMatchIndex: Int, endMatchIndex: Int): Boolean {
        return sequence.isWordSeparatorAt(startMatchIndex - 1)
                && sequence.isWordSeparatorAt(endMatchIndex + 1)
    }

    private fun StringBuilder.isWordSeparatorAt(index: Int): Boolean {
        return index < 0 || index >= this.length || this[index].toString().matches(wholeWordSeparator)
    }

    private fun CharSequence.indexOfLastWordSeparator(): Int {
        for (i in this.indices.reversed()) {
            if (this[i].toString().matches(wholeWordSeparator)) {
                return i
            }
        }
        return -1
    }

    private fun CharSequence.indexOfFirstWordSeparator(): Int {
        for (i in this.indices) {
            if (this[i].toString().matches(wholeWordSeparator)) {
                return i
            }
        }
        return -1
    }
}