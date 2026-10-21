package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCatalogTest {

    private fun leavesOf(nodes: List<TagNode>): List<String> {
        val result = mutableListOf<String>()
        fun walk(node: TagNode) {
            if (node.isLeaf) result += node.name else node.children.forEach(::walk)
        }
        nodes.forEach(::walk)
        return result
    }

    @Test
    fun `catalog is a non-empty tree`() {
        assertTrue(TagCatalog.tree.isNotEmpty())
        assertTrue(TagCatalog.tree.all { !it.isLeaf && it.children.isNotEmpty() })
    }

    @Test
    fun `leaf tag names are unique and non-blank`() {
        val leaves = leavesOf(TagCatalog.tree)
        assertTrue("应至少提供 50 个默认标签", leaves.size >= 50)
        assertEquals("标签名不能重复", leaves.size, leaves.toSet().size)
        assertTrue(leaves.none { it.isBlank() })
    }
}
