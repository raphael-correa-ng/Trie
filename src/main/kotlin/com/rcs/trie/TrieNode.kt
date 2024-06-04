package com.rcs.trie

class TrieNode<T>(
    val string: String,
    var value: T?,
    var size: Int,
    val next: MutableSet<TrieNode<T>>,
    val previous: TrieNode<T>?
) {

    fun completes(): Boolean {
        return value != null
    }
}