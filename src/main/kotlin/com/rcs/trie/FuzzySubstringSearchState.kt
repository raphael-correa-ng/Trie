package com.rcs.trie

data class FuzzySubstringSearchState<T>(
    val search: String,
    val node: Node<T>,
    val leftOfFirstMatchingCharacter: Node<T>?,
    var rightOfLastMatchingCharacter: Node<T>?,
    val searchIndex: Int,
    val numberOfMatches: Int,
    val numberOfErrors: Int,
    val errorTolerance: Int,
    val sequence: StringBuilder,
) {
    private val wholeWordSeparator = "[\\s\\p{P}]".toRegex()

    fun matches(): Boolean {
        return if (leftOfFirstMatchingCharacter == null) {
            return false
        } else if (node.completes() && numberOfMatches < search.length) {
            // the sequence being examined is shorter than the search input
            // however, it may still be a match
            getNumberOfErrorsIncludingMissingLetters() <= errorTolerance
        } else {
            searchIndex > search.length - 1
                    && numberOfMatches >= search.length - errorTolerance
                    && numberOfErrors <= errorTolerance
        }
    }

    fun nextBuildState(nextNode: Node<T>): FuzzySubstringSearchState<T> {
        val matchHasEnded = null != rightOfLastMatchingCharacter
        
        val nextNodeMatches = !matchHasEnded
                && numberOfMatches < search.length
                && nextNode.string == search[numberOfMatches].toString()

        val nextRightOfLastMatchingCharacter =
            if (!matchHasEnded && !nextNodeMatches) nextNode
            else rightOfLastMatchingCharacter

        val newNumberOfMatches =
            if (!matchHasEnded && nextNodeMatches) numberOfMatches + 1
            else numberOfMatches

        return FuzzySubstringSearchState(
            search = search,
            node = nextNode,
            leftOfFirstMatchingCharacter = leftOfFirstMatchingCharacter,
            rightOfLastMatchingCharacter = nextRightOfLastMatchingCharacter,
            searchIndex = searchIndex,
            numberOfMatches = newNumberOfMatches,
            numberOfErrors = numberOfErrors,
            errorTolerance = errorTolerance,
            sequence = StringBuilder(sequence).append(nextNode.string)
        )
    }

    private fun distanceToLastWordSeparatorIsPermissable(): Boolean {
        val indexOfLastWordSeparator = sequence.indexOfLastWordSeparator()
        val distanceToWordSeparator = sequence.length - 1 - indexOfLastWordSeparator
        return distanceToWordSeparator - 1 <= numberOfErrors
    }

    fun nextSearchStates(
        nextNode: Node<T>,
        matchingStrategy: FuzzySubstringMatchingStrategy
    ): Collection<FuzzySubstringSearchState<T>> {

        val nextStates = mutableListOf<FuzzySubstringSearchState<T>>()

        val wasMatchingBefore = numberOfMatches > 0

        val matchingPreconditions = when (matchingStrategy) {
            FuzzySubstringMatchingStrategy.LIBERAL ->
                true
            FuzzySubstringMatchingStrategy.MATCH_PREFIX ->
                wasMatchingBefore || node.isWordSeparator()
            else ->
                true
        }

        val nextNodeMatches = matchingPreconditions && nextNode.string == search[searchIndex].toString()

        // happy path - continue matching
        if (nextNodeMatches) {
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    leftOfFirstMatchingCharacter = leftOfFirstMatchingCharacter ?: node,
                    rightOfLastMatchingCharacter = null,
                    searchIndex = searchIndex + 1,
                    numberOfMatches = numberOfMatches + 1,
                    numberOfErrors = numberOfErrors,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )
            return nextStates
        }

        // was matching before, but no longer matches - however, there's some error tolerance to be used
        // there are three ways this can go:
        // 1. misspelling,
        // 2. missing letter in search input
        // 3. missing letter in data
        if (wasMatchingBefore && numberOfErrors < errorTolerance) {
            // 1. misspelling
            // increment searchIndex and go to the next node
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    leftOfFirstMatchingCharacter = leftOfFirstMatchingCharacter,
                    rightOfLastMatchingCharacter = null,
                    searchIndex = searchIndex + 1,
                    numberOfMatches = numberOfMatches,
                    numberOfErrors = numberOfErrors + 1,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )

            // 2. missing letter in target data
            // increment searchIndex and stay at the previous node
            if (searchIndex + 1 < search.length) {
                nextStates.add(
                    FuzzySubstringSearchState(
                        search = search,
                        node = node,
                        leftOfFirstMatchingCharacter = leftOfFirstMatchingCharacter,
                        rightOfLastMatchingCharacter = null,
                        searchIndex = searchIndex + 1,
                        numberOfMatches = numberOfMatches,
                        numberOfErrors = numberOfErrors + 1,
                        errorTolerance = errorTolerance,
                        sequence = StringBuilder(sequence)
                    )
                )
            }

            // 2. missing letter in search input
            // keep searchIndex the same and go to the next node
            nextStates.add(
                FuzzySubstringSearchState(
                    search = search,
                    node = nextNode,
                    leftOfFirstMatchingCharacter = leftOfFirstMatchingCharacter,
                    rightOfLastMatchingCharacter = null,
                    searchIndex = searchIndex,
                    numberOfMatches = numberOfMatches,
                    numberOfErrors = numberOfErrors + 1,
                    errorTolerance = errorTolerance,
                    sequence = StringBuilder(sequence).append(nextNode.string)
                )
            )
            return nextStates
        }

        // exhausted all attempts; reset matching
        nextStates.add(
            FuzzySubstringSearchState(
                search = search,
                node = nextNode,
                leftOfFirstMatchingCharacter = null,
                rightOfLastMatchingCharacter = null,
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
                distanceToLastWordSeparatorIsPermissable()
            else ->
                false
        }

        if (shouldConsiderMatchesWithWrongBeginning) {
            for (i in 1..errorTolerance - numberOfErrors) {
                nextStates.add(
                    FuzzySubstringSearchState(
                        search = search,
                        node = nextNode,
                        leftOfFirstMatchingCharacter = node,
                        rightOfLastMatchingCharacter = null,
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

    fun buildSearchResult(): TrieSearchResult<T> {
        assert(node.completes())

        val sequenceString = sequence.toString()

        val actualErrors = getNumberOfErrorsIncludingMissingLetters()

        val matchedWholeSequence = actualErrors == 0
                && matchedWholeSequence(leftOfFirstMatchingCharacter!!, rightOfLastMatchingCharacter)

        val matchedWholeWord = actualErrors == 0
                && matchedWholeWord(leftOfFirstMatchingCharacter!!, rightOfLastMatchingCharacter)

        return TrieSearchResult(
            sequenceString,
            node.value!!,
            numberOfMatches,
            actualErrors,
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
            } else if (searchIndex < search.length ) {
                val additionalWrongLetters = search.length - numberOfMatches
                search.length - numberOfMatches + additionalWrongLetters
            } else {
                numberOfErrors
            }
        } else {
            numberOfErrors
        }
    }

    private fun matchedWholeSequence(
        leftOfFirstMatchingCharacter: Node<T>,
        rightOfLastMatchingCharacter: Node<T>?
    ): Boolean {
        return leftOfFirstMatchingCharacter.string == ""
                && rightOfLastMatchingCharacter == null
    }

    private fun matchedWholeWord(
        leftOfFirstMatchingCharacter: Node<T>,
        rightOfLastMatchingCharacter: Node<T>?
    ): Boolean {
        return leftOfFirstMatchingCharacter.isWordSeparator()
                && rightOfLastMatchingCharacter.isWordSeparator()
    }

    private fun Node<T>?.isWordSeparator(): Boolean {
        return this == null || this.string == "" || this.string.matches(wholeWordSeparator)
    }

    private fun StringBuilder.indexOfLastWordSeparator(): Int {
        for (i in this.indices.reversed()) {
            if (this[i].toString().matches(wholeWordSeparator)) {
                return i
            }
        }
        return -1
    }
}
