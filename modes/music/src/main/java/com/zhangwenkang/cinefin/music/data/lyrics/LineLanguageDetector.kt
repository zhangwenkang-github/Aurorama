package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 逐行语言识别（MU-5 / REQUIREMENTS §10，纯函数、零依赖）。
 *
 * 规则（ARCHITECTURE §4.3）：
 * 1. 含平假名 / 片假名（含半角）→ [LyricLanguage.JAPANESE]（日文行几乎必然夹汉字，优先判假名）；
 * 2. 仅汉字 → [LyricLanguage.SIMPLIFIED_CHINESE] / [LyricLanguage.TRADITIONAL_CHINESE]（字形特征表）；
 * 3. 仅拉丁字母（含重音）→ [LyricLanguage.ENGLISH]；
 * 4. 汉字 + 拉丁字母混排 → [LyricLanguage.MIXED]（"中文行夹英文"，保留原文）；
 * 5. 其余（数字 / 符号 / 空白）→ [LyricLanguage.OTHER]。
 *
 * 规则法覆盖三样例；[ARCHITECTURE] §4.3 预留的 Lingua 兜底暂不引入（体积优先，详见 `MUSIC_PLAN` 决策）。
 */
object LineLanguageDetector {

    /**
     * 简 / 繁专用字形表：每段两个等长字符串按位对应（`简[i]` ↔ `繁[i]`）。
     *
     * 只收"只在一种字形中出现"的常用字，避免歧义；两字形相同或共用的字不参与判定。
     */
    internal val GLYPH_PAIRS: List<Pair<String, String>> =
        listOf(
            "爱们这说国时样为来后里发会对开关无见听让从还没过个给现点电话学习书车东长门马鸟风龙" to
                "愛們這說國時樣為來後裡發會對開關無見聽讓從還沒過個給現點電話學習書車東長門馬鳥風龍",
            "云气飞万与专业丛丝丢两严丧个丰临举义乌乐乔书习乡买乱争于亏亚产亩亲亿仅仆伧仑仓仪们价众优伙" to
                "雲氣飛萬與專業叢絲丟兩嚴喪個豐臨舉義烏樂喬書習鄉買亂爭於虧亞產畝親億僅僕傖侖倉儀們價眾優夥",
            "会伟传伤伦伪体余伥诈侧侨侬俩俭债倾偿储儿兑兰关兴养兽内冈册写军农冲决况冻净准凉减凑几凤凭凯" to
                "會偉傳傷倫偽體餘倀詐側僑儂倆儉債傾償儲兒兌蘭關興養獸內岡冊寫軍農衝決況凍淨準涼減湊幾鳳憑凱",
            "击凿刍划刘则刚创删别制剂剐剑剧劝办务动励劲劳势勋匀区医华协单卖卢卫却厂厅历厉压厌厕厦厨厩县叁" to
                "擊鑿芻劃劉則剛創刪別製劑剮劍劇勸辦務動勵勁勞勢勳勻區醫華協單賣盧衛卻廠廳歷厲壓厭廁廈廚廄縣參",
        )

    private val SIMPLIFIED_ONLY: Set<Char> = GLYPH_PAIRS.flatMap { it.first.toList() }.toSet()

    private val TRADITIONAL_ONLY: Set<Char> = GLYPH_PAIRS.flatMap { it.second.toList() }.toSet()

    fun detect(text: String): LyricLanguage {
        val hasKana = text.any(::isKana)
        if (hasKana) return LyricLanguage.JAPANESE

        val hasHan = text.any(::isHan)
        val hasLatin = text.any(::isLatinLetter)
        return when {
            hasHan && hasLatin -> LyricLanguage.MIXED
            hasHan -> chineseVariant(text)
            hasLatin -> LyricLanguage.ENGLISH
            else -> LyricLanguage.OTHER
        }
    }

    /** 汉字行的简 / 繁判定：专用字形各计一票，平票按项目语言优先级回落简体中文。 */
    private fun chineseVariant(text: String): LyricLanguage {
        var simplified = 0
        var traditional = 0
        for (char in text) {
            if (char in SIMPLIFIED_ONLY) simplified++
            if (char in TRADITIONAL_ONLY) traditional++
        }
        return if (traditional > simplified) {
            LyricLanguage.TRADITIONAL_CHINESE
        } else {
            LyricLanguage.SIMPLIFIED_CHINESE
        }
    }

    /** 平假名 / 片假名 / 半角片假名 / 假名长音。 */
    private fun isKana(char: Char): Boolean =
        char in '\u3040'..'\u30FF' || char in '\uFF66'..'\uFF9D' || char == '\u30FC'

    /** CJK 统一表意文字（含扩展 A / 兼容区）。 */
    private fun isHan(char: Char): Boolean =
        char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF' || char in '\uF900'..'\uFAFF'

    /** 拉丁字母（Basic Latin + Latin-1 补充 + 扩展 A/B 常见区间）。 */
    private fun isLatinLetter(char: Char): Boolean =
        char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char in '\u00C0'..'\u024F' ||
            char in '\u1E00'..'\u1EFF'
}
