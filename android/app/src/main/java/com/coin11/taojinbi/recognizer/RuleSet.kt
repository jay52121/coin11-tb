package com.coin11.taojinbi.recognizer

import org.json.JSONObject

data class RuleSet(
    val actionTextPattern: String,
    val doneWords: List<String>,
    val searchBrowseWords: List<String>,
    val coinHomeWords: List<String>,
    val coinHomeTaskWords: List<String>,
    val browsePageWords: List<String>,
    val dailyFastWords: List<String>,
    val dailyTaskAreaWords: List<String>,
    val taskListWords: List<String>,
    val taskListBottomWords: List<String>,
    val taskDonePageWords: List<String>,
    val taskDoneExcludeWords: List<String>,
    val quizWords: List<String>,
    val shopSubscribeWords: List<String>,
) {
    companion object {
        val DEFAULT = RuleSet(
            actionTextPattern = "去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下|点击去逛|领取奖励|立即领取|点击得|爱心捐",
            doneWords = listOf("已完成", "已领取", "已得", "任务已完成", "记得明天再来"),
            searchBrowseWords = listOf("搜索后浏览立得奖励", "搜索有福利", "淘宝精选", "搜索发现", "历史搜索"),
            coinHomeWords = listOf("淘金币首页", "淘金币标题", "可抵", "购物车", "赚金币抵钱", "赚更多金币"),
            coinHomeTaskWords = listOf("今日速赚", "快速赚", "完成下方任务", "更多金币等你赚", "任务到访得金币", "每日来任务面板"),
            browsePageWords = listOf("浏览", "浏览25秒", "已得", "累计已得", "累积已得", "直播攒红包", "热销", "爆款", "抵扣", "金币热卖价", "近七天卖出", "已售"),
            dailyFastWords = listOf("今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来"),
            dailyTaskAreaWords = listOf("完成下方任务", "更多金币等你赚", "展开", "任务到访得金币", "每日来任务面板", "逛清单", "淘金币趣味课堂", "浏览15秒"),
            taskListWords = listOf("今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来", "完成下方任务", "更多金币等你赚", "展开", "任务到访得金币", "每日来任务面板", "逛清单", "淘金币趣味课堂", "领取奖励", "去完成", "去逛逛", "点击去逛"),
            taskListBottomWords = listOf("收起更多任务"),
            taskDonePageWords = listOf("任务已完成", "已得"),
            taskDoneExcludeWords = listOf("累计已得", "累积已得"),
            quizWords = listOf("淘金币趣味答题", "我选好了"),
            shopSubscribeWords = listOf("订阅+", "已关注", "取消关注", "最多还可以领", "立即领"),
        )

        fun fromJson(json: JSONObject): RuleSet {
            fun strings(key: String, fallback: List<String>): List<String> {
                val array = json.optJSONArray(key) ?: return fallback
                return buildList {
                    for (index in 0 until array.length()) {
                        val value = array.optString(index).trim()
                        if (value.isNotEmpty()) add(value)
                    }
                }.ifEmpty { fallback }
            }

            return DEFAULT.copy(
                actionTextPattern = json.optString(
                    "action_text_pattern",
                    DEFAULT.actionTextPattern,
                ).ifBlank { DEFAULT.actionTextPattern },
                doneWords = strings("done_words", DEFAULT.doneWords),
                searchBrowseWords = strings("search_browse_words", DEFAULT.searchBrowseWords),
                coinHomeWords = strings("coin_home_words", DEFAULT.coinHomeWords),
                coinHomeTaskWords = strings("coin_home_task_words", DEFAULT.coinHomeTaskWords),
                browsePageWords = strings("browse_page_words", DEFAULT.browsePageWords),
                dailyFastWords = strings("daily_fast_words", DEFAULT.dailyFastWords),
                dailyTaskAreaWords = strings("daily_task_area_words", DEFAULT.dailyTaskAreaWords),
                taskListWords = strings("task_list_words", DEFAULT.taskListWords),
                taskListBottomWords = strings("task_list_bottom_words", DEFAULT.taskListBottomWords),
                taskDonePageWords = strings("task_done_page_words", DEFAULT.taskDonePageWords),
                taskDoneExcludeWords = strings("task_done_exclude_words", DEFAULT.taskDoneExcludeWords),
                quizWords = strings("quiz_words", DEFAULT.quizWords),
                shopSubscribeWords = strings("shop_subscribe_words", DEFAULT.shopSubscribeWords),
            )
        }
    }
}
