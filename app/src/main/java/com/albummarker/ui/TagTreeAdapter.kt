package com.albummarker.ui

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.albummarker.R
import com.albummarker.data.TagNode

/**
 * 「选择标签」弹窗里的树形多选列表。
 *
 * - 分组行可展开/收起（默认展开一级主题，二级分组收起）；
 * - 叶子行是复选框，可多选；
 * - 当前照片已有的标签显示为「已添加」且不可再勾选。
 */
class TagTreeAdapter(
    private val tree: List<TagNode>,
    private val existing: Set<String>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_LEAF = 1
    }

    /** 勾选变化回调，宿主用来刷新「添加（N）」按钮。 */
    var onSelectionChanged: (() -> Unit)? = null

    private sealed class Row {
        data class Group(val node: TagNode, val path: String, val depth: Int, val expanded: Boolean) : Row()
        data class Leaf(val name: String, val depth: Int, val alreadyAdded: Boolean) : Row()
    }

    /** 默认展开一级主题，方便先看到主题/分组结构。 */
    private val expanded: MutableSet<String> =
        tree.filter { !it.isLeaf }.mapTo(mutableSetOf()) { it.name }

    private val selected = linkedSetOf<String>()
    private var rows: List<Row> = buildRows()

    val selectedTags: List<String> get() = selected.toList()

    private fun buildRows(): List<Row> {
        val result = mutableListOf<Row>()
        fun walk(nodes: List<TagNode>, depth: Int, parentPath: String) {
            nodes.forEach { node ->
                val path = if (parentPath.isEmpty()) node.name else "$parentPath/${node.name}"
                if (node.isLeaf) {
                    result += Row.Leaf(node.name, depth, existing.contains(node.name))
                } else {
                    val open = expanded.contains(path)
                    result += Row.Group(node, path, depth, open)
                    if (open) walk(node.children, depth + 1, path)
                }
            }
        }
        walk(tree, 0, "")
        return result
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Group) TYPE_GROUP else TYPE_LEAF

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            GroupHolder(inflater.inflate(R.layout.item_tag_tree_group, parent, false))
        } else {
            LeafHolder(inflater.inflate(R.layout.item_tag_tree_leaf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Group -> (holder as GroupHolder).bind(row)
            is Row.Leaf -> (holder as LeafHolder).bind(row)
        }
    }

    private inner class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val arrow: ImageView = view.findViewById(R.id.ivArrow)
        private val name: TextView = view.findViewById(R.id.tvTagGroup)
        private val count: TextView = view.findViewById(R.id.tvTagCount)

        fun bind(row: Row.Group) {
            val context = itemView.context
            itemView.setPaddingRelative(dp(context, 12 + row.depth * 18), 0, dp(context, 12), 0)
            arrow.rotation = if (row.expanded) 90f else 0f
            name.text = row.node.name
            count.text = context.getString(R.string.tag_group_count, leafCount(row.node))
            itemView.setOnClickListener {
                if (row.expanded) expanded.remove(row.path) else expanded.add(row.path)
                rows = buildRows()
                notifyDataSetChanged()
            }
        }
    }

    private inner class LeafHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val checkBox: CheckBox = view.findViewById(R.id.cbTag)

        fun bind(row: Row.Leaf) {
            val context = itemView.context
            // 缩进放在外层容器上：CheckBox 的方框不会跟随自身 padding 移动
            itemView.setPaddingRelative(
                dp(context, 12 + row.depth * 18),
                0,
                dp(context, 12),
                0,
            )
            checkBox.setOnCheckedChangeListener(null)
            checkBox.text = if (row.alreadyAdded) {
                context.getString(R.string.tag_marked_added, row.name)
            } else {
                row.name
            }
            checkBox.isChecked = row.alreadyAdded || selected.contains(row.name)
            checkBox.isEnabled = !row.alreadyAdded
            checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(row.name) else selected.remove(row.name)
                onSelectionChanged?.invoke()
            }
        }
    }

    private fun leafCount(node: TagNode): Int =
        if (node.isLeaf) 1 else node.children.sumOf { leafCount(it) }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
