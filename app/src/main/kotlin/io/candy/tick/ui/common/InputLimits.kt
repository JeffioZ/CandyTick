package io.candy.tick.ui.common

/**
 * 输入框长度上限（单源）：长文本粘贴的防呆上限，不影响正常输入。
 * 过滤在 onValueChange 层做（超限静默忽略新增字符）。
 * 只对「有官方依据可循」的字段设限；无依据的不限（如 Token——官方无长度文档，
 * 其正确性由 Save & verify 的服务端验证兜底，前端上限反而可能废掉未来的有效格式）。
 */
object InputLimits {
    /** 搜索/链接/过滤类：覆盖 GitHub 搜索查询上限（256，官方文档）与最长仓库 URL（owner 39 + repo 100，官方文档 + scheme ≈ 159） */
    const val SEARCH = 256
}
