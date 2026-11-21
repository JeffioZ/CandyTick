package io.candy.tick.ui.common

/**
 * 输入框长度上限（单源）：长文本粘贴的防呆上限，不影响正常输入。
 * 过滤在 onValueChange 层做（超限静默忽略新增字符）。
 */
object InputLimits {
    /** 搜索/链接/过滤类：覆盖 GitHub 搜索查询上限（256）与最长仓库 URL（owner 39 + repo 100 + scheme ≈ 159） */
    const val SEARCH = 256

    /** GitHub Token：fine-grained PAT ≈93 字符，留余量 */
    const val TOKEN = 128
}
