package com.zhangwenkang.cinefin.language

/**
 * 语言标签归一化与匹配。
 *
 * 实际使用中，语言线索来自三个完全不同的地方：
 * 1. Jellyfin 元数据（`language` 字段，可能是 ISO 639-2 的 chi/eng/jpn，也可能为空）
 * 2. 轨道标题（可能是“简体中文”“Chinese (Simplified)”“chs”这类自由文本）
 * 3. 外挂字幕文件名（`xxx.zh-Hans.ass`、`xxx.chs.srt`）
 *
 * 这里把三者统一归一化为 BCP-47 风格标签（zh-Hans / zh-Hant / zh / en / ja ...）， 再按用户定义的优先级列表打分，从而选出“用户最想看的那条字幕”。
 */
object LanguageMatcher {
    const val CHINESE_SIMPLIFIED = "zh-Hans"
    const val CHINESE_TRADITIONAL = "zh-Hant"
    const val CHINESE = "zh"
    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"

    /** 默认字幕优先级：简体中文 > 繁体中文 > 中文（未区分）> 英文 */
    val DEFAULT_SUBTITLE_PRIORITY =
        listOf(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, CHINESE, ENGLISH)

    /** 默认音轨优先级：优先中文，其次日语原声，最后英文 */
    val DEFAULT_AUDIO_PRIORITY =
        listOf(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, CHINESE, JAPANESE, ENGLISH)

    /** 设置页里可勾选的语言（顺序即默认优先级） */
    val SELECTABLE_LANGUAGES =
        listOf(
            CHINESE_SIMPLIFIED,
            CHINESE_TRADITIONAL,
            CHINESE,
            ENGLISH,
            JAPANESE,
            KOREAN,
            "fr",
            "de",
            "es",
            "pt",
            "ru",
            "it",
        )

    /** 关键字表：键为小写、已去除空格的线索，值为规范标签。 顺序敏感——更具体的关键字必须排在更宽泛的关键字之前（按长度倒序匹配）。 */
    private val KEYWORDS: List<Pair<String, String>> =
        listOf(
                // 简体中文
                "zh-hans" to CHINESE_SIMPLIFIED,
                "zh-cn" to CHINESE_SIMPLIFIED,
                "zh-sg" to CHINESE_SIMPLIFIED,
                "zh-my" to CHINESE_SIMPLIFIED,
                "chi-hans" to CHINESE_SIMPLIFIED,
                "zho-hans" to CHINESE_SIMPLIFIED,
                "cmn-hans" to CHINESE_SIMPLIFIED,
                "simplifiedchinese" to CHINESE_SIMPLIFIED,
                "chinesesimplified" to CHINESE_SIMPLIFIED,
                "chinese(simplified)" to CHINESE_SIMPLIFIED,
                "简体中文" to CHINESE_SIMPLIFIED,
                "简体" to CHINESE_SIMPLIFIED,
                "简中" to CHINESE_SIMPLIFIED,
                "中文简体" to CHINESE_SIMPLIFIED,
                "简体字幕" to CHINESE_SIMPLIFIED,
                "chs" to CHINESE_SIMPLIFIED,
                "sc" to CHINESE_SIMPLIFIED,
                "gb" to CHINESE_SIMPLIFIED,
                // 单字线索（W74）：`简日双语` / `繁日雙語` / `简日[CHS-JPN]` 这类双语轨标题里
                // 只有「简」/「繁」一个字能定简繁，靠更长的关键字匹配不到。
                "简" to CHINESE_SIMPLIFIED,
                "簡" to CHINESE_SIMPLIFIED,
                // 繁体中文
                "zh-hant" to CHINESE_TRADITIONAL,
                "zh-tw" to CHINESE_TRADITIONAL,
                "zh-hk" to CHINESE_TRADITIONAL,
                "zh-mo" to CHINESE_TRADITIONAL,
                "chi-hant" to CHINESE_TRADITIONAL,
                "zho-hant" to CHINESE_TRADITIONAL,
                "traditionalchinese" to CHINESE_TRADITIONAL,
                "chinesetraditional" to CHINESE_TRADITIONAL,
                "chinese(traditional)" to CHINESE_TRADITIONAL,
                "繁體中文" to CHINESE_TRADITIONAL,
                "繁体中文" to CHINESE_TRADITIONAL,
                "繁體" to CHINESE_TRADITIONAL,
                "繁体" to CHINESE_TRADITIONAL,
                "繁中" to CHINESE_TRADITIONAL,
                "正体" to CHINESE_TRADITIONAL,
                "cht" to CHINESE_TRADITIONAL,
                "tc" to CHINESE_TRADITIONAL,
                "big5" to CHINESE_TRADITIONAL,
                // 单字线索（W74）：`繁日雙語` / `繁体` 这类标题里「繁」字即繁体
                "繁" to CHINESE_TRADITIONAL,
                // 中文（未区分简繁）
                "zh" to CHINESE,
                "chi" to CHINESE,
                "zho" to CHINESE,
                "cmn" to CHINESE,
                "mandarin" to CHINESE,
                "chinese" to CHINESE,
                "中文" to CHINESE,
                "中字" to CHINESE,
                "中文字幕" to CHINESE,
                "国语" to CHINESE,
                "國語" to CHINESE,
                "汉语" to CHINESE,
                "漢語" to CHINESE,
                "华语" to CHINESE,
                // 英语
                "en" to ENGLISH,
                "eng" to ENGLISH,
                "english" to ENGLISH,
                "英语" to ENGLISH,
                "英文" to ENGLISH,
                // 日语
                "ja" to JAPANESE,
                "jpn" to JAPANESE,
                "japanese" to JAPANESE,
                "日本語" to JAPANESE,
                "日语" to JAPANESE,
                "日文" to JAPANESE,
                // 韩语
                "ko" to KOREAN,
                "kor" to KOREAN,
                "korean" to KOREAN,
                "한국어" to KOREAN,
                "韩语" to KOREAN,
                "韓語" to KOREAN,
            )
            .map { (keyword, tag) -> keyword.replace(" ", "") to tag }
            .sortedByDescending { it.first.length }

    /** ISO 639-2/B 与 639-2/T 到 639-1 的映射（常见语言） */
    private val ISO_639_2_TO_1 =
        mapOf(
            "chi" to "zh",
            "zho" to "zh",
            "cmn" to "zh",
            "eng" to "en",
            "jpn" to "ja",
            "kor" to "ko",
            "fra" to "fr",
            "fre" to "fr",
            "deu" to "de",
            "ger" to "de",
            "spa" to "es",
            "por" to "pt",
            "rus" to "ru",
            "ita" to "it",
            "ara" to "ar",
            "hin" to "hi",
            "tha" to "th",
            "vie" to "vi",
            "ind" to "id",
            "tur" to "tr",
            "nld" to "nl",
            "dut" to "nl",
            "pol" to "pl",
            "swe" to "sv",
            "dan" to "da",
            "nor" to "no",
            "fin" to "fi",
            "ces" to "cs",
            "cze" to "cs",
            "ell" to "el",
            "gre" to "el",
            "heb" to "he",
            "ukr" to "uk",
            "ron" to "ro",
            "rum" to "ro",
            "bul" to "bg",
            "hun" to "hu",
            "rus " to "ru",
        )

    private val KNOWN_BASE_LANGUAGES =
        setOf(
            "zh",
            "en",
            "ja",
            "ko",
            "fr",
            "de",
            "es",
            "pt",
            "ru",
            "it",
            "ar",
            "hi",
            "th",
            "vi",
            "id",
            "tr",
            "nl",
            "pl",
            "sv",
            "da",
            "no",
            "fi",
            "cs",
            "el",
            "he",
            "uk",
            "ro",
            "bg",
            "hu",
        )

    /** 语言标签的中文显示名，用于设置页与轨道列表 */
    private val DISPLAY_NAMES =
        mapOf(
            CHINESE_SIMPLIFIED to "简体中文",
            CHINESE_TRADITIONAL to "繁体中文",
            CHINESE to "中文",
            ENGLISH to "英语",
            JAPANESE to "日语",
            KOREAN to "韩语",
            "fr" to "法语",
            "de" to "德语",
            "es" to "西班牙语",
            "pt" to "葡萄牙语",
            "ru" to "俄语",
            "it" to "意大利语",
        )

    fun displayName(tag: String): String = DISPLAY_NAMES[tag] ?: tag

    /** 解析逗号分隔的优先级配置；为空或非法时回退到默认值 */
    fun parsePriority(raw: String?, default: List<String>): List<String> {
        val parsed = raw?.split(',')?.mapNotNull { normalize(it) }?.distinct().orEmpty()
        return parsed.ifEmpty { default }
    }

    fun priorityToString(priority: List<String>): String = priority.joinToString(",")

    /** 由“首选语言”推导完整的优先级列表：首选置顶，其余保持默认顺序， 并剔除同语言族的重复项（例如首选繁体中文时不再保留简体中文之外的 zh）。 */
    fun buildPriority(rawPrimary: String?, default: List<String>): List<String> {
        val primary = normalize(rawPrimary) ?: return default
        val base = baseOf(primary)
        return listOf(primary) + default.filterNot { baseOf(it) == base }
    }

    /**
     * 把优先级列表项（归一化 BCP-47 标签）映射回设置页下拉的选项值（W74 U7）。
     *
     * 设置页「首选语言」下拉的候选值域是 ISO 639-2 与 BCP-47 的混合（`chi` / `zh-Hans` / `eng` …）， 而优先级列表存的是归一化标签（`zh` /
     * `zh-Hans` / `en` …）：先归一化再在候选值域里找第一项， 找不到时返回 null（下拉显示「未设置」）。这样手动选轨只改 `pref_*_languages`
     * 也不会让设置页读数滞后。
     */
    fun optionValueFor(
        tag: String?,
        optionValues: List<String>,
    ): String? {
        val target = normalize(tag) ?: return null
        return optionValues.firstOrNull { normalize(it) == target }
    }

    /** 取语言基础码，例如 zh-Hans → zh、en-US → en */
    fun baseOf(tag: String): String = tag.substringBefore('-').lowercase()

    /** 把任意语言线索归一化为规范标签；无法识别时返回 null。 */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.trim().lowercase().replace('_', '-')
        if (compact == "und" || compact == "unknown" || compact == "未指定") return null

        // 1. 关键字命中（含中文/英文描述、常见缩写）
        val flat = compact.replace(" ", "")
        KEYWORDS.firstOrNull { flat.contains(it.first) }
            ?.let {
                return it.second
            }

        // 2. BCP-47 结构：语言[-文字][-地区]
        val parts = compact.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val base = ISO_639_2_TO_1[parts[0]] ?: parts[0]
        if (base.length != 2 || base !in KNOWN_BASE_LANGUAGES) return null

        val script = parts.drop(1).firstOrNull { it.length == 4 }
        return when {
            base == "zh" && script == "hans" -> CHINESE_SIMPLIFIED
            base == "zh" && script == "hant" -> CHINESE_TRADITIONAL
            script != null -> "$base-${script.replaceFirstChar { it.uppercase() }}"
            else -> base
        }
    }

    /**
     * 依次尝试多个线索，返回**最具体**的可识别结果（W74 #14）。
     *
     * Jellyfin 对中文字幕普遍只写泛化的语言码（`chi` / `zho`），此时「简体 / 繁体」的唯一线索在轨道标题里。
     * 旧实现「第一个能识别的线索就返回」会被泛化的语言码抢占：简中和繁体字幕都被归一化成 `zh`， 落到同一优先级后只能靠轨道序号 / 默认轨决定，真机表现就是「自动选到了繁体」。
     *
     * 规则：语言码已经给出具体变体（`zh-Hans` / `zh-Hant`）时以它为准；只是泛化值时， 允许**同一语言族**里更具体的线索（标题 /
     * 文件名）覆盖它。不同语言族之间保持「先给的线索优先」， 避免标题里的杂词把语言整个改掉。
     */
    fun detect(vararg hints: String?): String? {
        val tags = hints.mapNotNull { normalize(it) }
        val first = tags.firstOrNull() ?: return null
        if (first.contains('-')) return first
        return tags.firstOrNull { it != first && baseOf(it) == baseOf(first) } ?: first
    }

    /** 从外挂字幕文件名推断语言，例如： `Movie.2013.zh-Hans.ass` → zh-Hans，`动画.chs.srt` → zh-Hans */
    fun fromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        return normalize(name)
    }

    /**
     * 判断轨道标签 [tag] 是否满足偏好 [preferred]：
     * - 完全相同算命中
     * - 偏好更宽泛（zh）时，任何中文变体（zh-Hans/zh-Hant）都算命中
     * - 偏好更具体（zh-Hans）时，只认具体变体，避免把繁体当成简体
     */
    fun matches(tag: String?, preferred: String): Boolean {
        if (tag.isNullOrBlank()) return false
        if (tag.equals(preferred, ignoreCase = true)) return true
        val preferredBase = baseOf(preferred)
        if (baseOf(tag) != preferredBase) return false
        return !preferred.contains('-')
    }

    /** 返回 [tag] 在优先级列表中的位置（0 最佳）；未命中返回 null。 */
    fun priorityIndex(tag: String?, priority: List<String>): Int? {
        if (tag.isNullOrBlank()) return null
        priority.forEachIndexed { index, preferred -> if (matches(tag, preferred)) return index }
        return null
    }
}
