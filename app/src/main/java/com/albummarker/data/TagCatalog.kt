package com.albummarker.data

/** 标签树节点：没有子节点时即为一个可选标签。 */
data class TagNode(
    val name: String,
    val children: List<TagNode> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/**
 * 默认标签目录：按主题组织的三层树（主题 → 分组 → 标签）。
 *
 * 这里只维护「可选项」清单；用户真正选中的标签仍以文件 SHA-256 为键
 * 写入 tags / media_tags 表，和手动输入、自动打标共用同一套存储。
 * 默认清单是静态的，后续要做「自定义标签」时可以在这一层扩展持久化。
 */
object TagCatalog {

    private fun leaf(vararg names: String): List<TagNode> = names.map { TagNode(it) }

    val tree: List<TagNode> = listOf(
        TagNode(
            "人物", listOf(
                TagNode("单人", leaf("自拍", "写真", "背影")),
                TagNode("多人", leaf("合影", "聚会", "情侣")),
                TagNode("家庭", leaf("家人", "孩子", "长辈")),
            )
        ),
        TagNode(
            "场景", listOf(
                TagNode("自然", leaf("风景", "山川", "海边", "天空", "日落")),
                TagNode("城市", leaf("街道", "建筑", "夜景", "车站")),
                TagNode("室内", leaf("家居", "餐厅", "商场", "办公室")),
            )
        ),
        TagNode(
            "活动", listOf(
                TagNode("出行", leaf("旅行", "自驾", "徒步", "露营")),
                TagNode("娱乐", leaf("演出", "游戏", "观影", "运动")),
                TagNode("生活", leaf("购物", "读书", "做饭")),
            )
        ),
        TagNode(
            "美食", listOf(
                TagNode("中餐", leaf("家常菜", "火锅", "烧烤")),
                TagNode("西餐", leaf("牛排", "意面", "披萨")),
                TagNode("甜点饮品", leaf("蛋糕", "冰淇淋", "咖啡", "茶")),
            )
        ),
        TagNode(
            "物品", listOf(
                TagNode("数码", leaf("手机", "电脑", "耳机", "相机")),
                TagNode("生活", leaf("书籍", "服饰", "花草", "家具")),
                TagNode("交通", leaf("汽车", "自行车", "飞机", "火车")),
            )
        ),
        TagNode(
            "风格", listOf(
                TagNode("构图", leaf("人像", "街拍", "特写", "全景")),
                TagNode("色调", leaf("黑白", "复古", "胶片", "极简")),
            )
        ),
        TagNode(
            "情绪", listOf(
                TagNode("正面", leaf("开心", "温馨", "治愈", "惊喜")),
                TagNode("氛围", leaf("宁静", "热闹", "怀旧", "孤独")),
            )
        ),
        TagNode(
            "地点", listOf(
                TagNode("常去", leaf("家", "公司", "学校", "公园")),
                TagNode("出行", leaf("景点", "酒店", "机场", "民宿")),
            )
        ),
        TagNode(
            "时间", listOf(
                TagNode("日常", leaf("早晨", "午后", "傍晚", "深夜")),
                TagNode("节点", leaf("生日", "纪念日", "节日", "假期")),
            )
        ),
        TagNode(
            "学习工作", listOf(
                TagNode("工作", leaf("会议", "办公", "文档", "代码")),
                TagNode("学习", leaf("笔记", "课程", "证书", "阅读")),
            )
        ),
    )
}
