package com.zhangwenkang.cinefin.book.presentation.reader

import com.zhangwenkang.cinefin.settings.domain.TxtChapterRules
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** TXT 编码档位（W6-T7）：只认 UTF-8 与 GBK 超集 GB18030（中文网文两大来源）。 */
enum class TxtEncoding(val charset: Charset, val label: String) {
    Utf8(Charsets.UTF_8, "UTF-8"),
    Gb18030(Charset.forName("GB18030"), "GB18030"),
}

/** TXT 解码结果：编码 + 去掉 BOM 的全文。 */
data class TxtDocument(val encoding: TxtEncoding, val text: String)

/** 分章结果：`title` = 章头行（空 = 无章头，整书单章兜底）；`body` = 该章正文（不含章头行）。 */
data class TxtChapter(val title: String?, val body: String)

/**
 * TXT → 缓存 EPUB 转换器（READER-37 TXT 半，W6-T7）。
 *
 * 纯 JVM（无 Android 依赖），单测直接喂字节数组：
 * 1. 编码探测：UTF-8 严格解码失败才退 GB18030；NUL / 控制字符占比过高判二进制，不认作文本；
 * 2. 分章：按 [TxtChapterRules] 正则逐行切 spine，章头行必须落在行首、长度有限、不带句末标点； 无命中 → 整书单章；
 * 3. 打包：标准 ZIP + OCF（mimetype 首条 STORED）+ OPF / NAV / NCX 最小 EPUB 3 结构， 不依赖任何第三方库；输出确定（时间戳固定），缓存重建后
 *    locator / 进度仍能对上。
 */
object TxtToEpub {

    /** 章头行最大长度：超过视为正文（防止「第一章里他……」这类长句被切成章）。 */
    const val MAX_HEADING_LENGTH = 60

    /** 单段落上限：超长行（整本书一行）按句读再切，避免 Readium 分页卡在巨型 `<p>`。 */
    private const val MAX_PARAGRAPH_CHARS = 1200

    private const val PARAGRAPH_TARGET_CHARS = 800

    /** ZIP 条目固定时间戳（2020-01-01T00:00:00Z）：保证重复转换字节一致。 */
    private const val ZIP_ENTRY_TIME_MS = 1_577_836_800_000L

    /** EPUB 3 必填的 dcterms:modified：固定值，理由同 [ZIP_ENTRY_TIME_MS]。 */
    private const val EPUB_MODIFIED = "2020-01-01T00:00:00Z"

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** 句末标点：章头行出现即当正文（章头几乎不会以句号结尾）。 */
    private const val SENTENCE_PUNCTUATION = "。！？；!?;"

    /**
     * 格式嗅探用编码探测：只看 [length] 字节（探针），返回 null = 判为非文本（二进制 / 不可解码）。
     *
     * 先做字节级二进制防护再做解码：NUL 或控制字符 > 2% 直接否决，避免随机字节被 GB18030 宽松解码吞下。
     */
    fun detectEncoding(bytes: ByteArray, length: Int = bytes.size): TxtEncoding? {
        val limit = length.coerceIn(0, bytes.size)
        if (limit <= 0) return null
        if (looksBinary(bytes, limit)) return null
        if (startsWithBom(bytes, limit)) return TxtEncoding.Utf8
        if (decodable(bytes, limit, TxtEncoding.Utf8.charset)) return TxtEncoding.Utf8
        if (decodable(bytes, limit, TxtEncoding.Gb18030.charset)) return TxtEncoding.Gb18030
        return null
    }

    /** 全文解码；null = 不是可读文本（编码探测失败或几乎没有文字内容）。 */
    fun decode(bytes: ByteArray): TxtDocument? {
        val encoding = detectEncoding(bytes) ?: return null
        val text = String(bytes, encoding.charset).removePrefix("\uFEFF")
        if (text.isBlank() || text.none { it.isLetterOrDigit() }) return null
        return TxtDocument(encoding, text)
    }

    /**
     * 按 [chapterRegex] 切章；非法正则回落 [TxtChapterRules.DEFAULT_CHAPTER_REGEX]。
     *
     * 首个章头之前的非空内容独立成章（标题「开篇」）；整本无章头 → 单章、`title = null`。
     */
    fun splitChapters(
        text: String,
        chapterRegex: String = TxtChapterRules.DEFAULT_CHAPTER_REGEX,
    ): List<TxtChapter> {
        val pattern = Regex(TxtChapterRules.resolve(chapterRegex))
        val chapters = mutableListOf<TxtChapter>()
        val body = StringBuilder()
        var currentTitle: String? = null
        var inChapter = false

        fun flush() {
            val content = body.toString().trim('\n', '\r').trimEnd()
            if (inChapter || content.isNotBlank()) {
                chapters += TxtChapter(currentTitle, content)
            }
            body.setLength(0)
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && isHeading(pattern, trimmed)) {
                flush()
                currentTitle = trimmed
                inChapter = true
            } else {
                body.append(line).append('\n')
            }
        }
        flush()

        // 章头之前的正文挂「开篇」；整本无章头时保持 title = null（导航里显示「正文」）。
        if (chapters.size > 1 && chapters.first().title == null) {
            chapters[0] = chapters[0].copy(title = "开篇")
        }
        return chapters
    }

    /** 章节 → XHTML + OPF / NAV / NCX / CSS 的最小 EPUB 3 写入 [out]。 */
    fun writeEpub(
        chapters: List<TxtChapter>,
        title: String,
        bookId: UUID,
        out: OutputStream,
        language: String? = null,
    ) {
        require(chapters.isNotEmpty()) { "TXT 中没有可阅读的章节" }
        val lang = language ?: detectLanguage(chapters)
        ZipOutputStream(out).use { zip ->
            zip.putStored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.putStored("META-INF/container.xml", containerXml().toByteArray())
            zip.putStored("OEBPS/style.css", STYLE_CSS.toByteArray())
            chapters.forEachIndexed { index, chapter ->
                val href = chapterHref(index)
                zip.putStored("OEBPS/$href", chapterXhtml(chapter, href, lang).toByteArray())
            }
            zip.putStored("OEBPS/nav.xhtml", navXhtml(chapters, lang).toByteArray())
            zip.putStored("OEBPS/toc.ncx", tocNcx(chapters, title, bookId, lang).toByteArray())
            zip.putStored(
                "OEBPS/content.opf",
                contentOpf(chapters, title, bookId, lang).toByteArray(),
            )
        }
    }

    /** 一次性转换：探测 / 解码 / 分章 / 写 EPUB；返回使用的编码（null = 不是可读文本）。 */
    fun convert(
        bytes: ByteArray,
        title: String,
        bookId: UUID,
        out: OutputStream,
        chapterRegex: String = TxtChapterRules.DEFAULT_CHAPTER_REGEX,
    ): TxtEncoding? {
        val document = decode(bytes) ?: return null
        writeEpub(
            chapters = splitChapters(document.text, chapterRegex),
            title = title,
            bookId = bookId,
            out = out,
        )
        return document.encoding
    }

    /** 文件名 → 书名：去扩展名，空名回落「未命名书籍」。 */
    fun titleOf(fileName: String): String {
        val name = fileName.substringBeforeLast('.', fileName).trim()
        return name.ifEmpty { fileName.ifBlank { "未命名书籍" } }
    }

    // ---- 编码探测 ----

    private fun looksBinary(bytes: ByteArray, limit: Int): Boolean {
        var control = 0
        for (index in 0 until limit) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value == 0 -> return true
                value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D && value != 0x0C ->
                    control++
                value == 0x7F -> control++
            }
        }
        return control * 100 > limit * 2
    }

    private fun startsWithBom(bytes: ByteArray, limit: Int): Boolean =
        limit >= UTF8_BOM.size &&
            bytes[0] == UTF8_BOM[0] &&
            bytes[1] == UTF8_BOM[1] &&
            bytes[2] == UTF8_BOM[2]

    /**
     * 严格解码是否通过。
     *
     * 探针可能从多字节字符中间截断，所以允许削掉末尾 0..3 字节重试；正文中间的非法字节没有这种豁免。
     */
    private fun decodable(bytes: ByteArray, limit: Int, charset: Charset): Boolean {
        val decoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        for (trim in 0..3) {
            val end = limit - trim
            if (end <= 0) return false
            try {
                decoder.reset()
                decoder.decode(ByteBuffer.wrap(bytes, 0, end))
                return true
            } catch (_: CharacterCodingException) {
                // 削尾再试
            }
        }
        return false
    }

    // ---- 分章 ----

    private fun isHeading(pattern: Regex, line: String): Boolean {
        if (line.length > MAX_HEADING_LENGTH) return false
        if (line.any { it in SENTENCE_PUNCTUATION }) return false
        val match = pattern.find(line) ?: return false
        return match.range.first == 0
    }

    // ---- EPUB 打包 ----

    private fun chapterHref(index: Int): String =
        "chapter-${(index + 1).toString().padStart(4, '0')}.xhtml"

    private fun chapterLabel(chapter: TxtChapter): String =
        chapter.title?.takeIf { it.isNotBlank() } ?: "正文"

    private fun containerXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """
            .trimIndent()

    private fun chapterXhtml(chapter: TxtChapter, href: String, lang: String): String {
        val heading =
            chapter.title
                ?.takeIf { it.isNotBlank() }
                ?.let { "<h1>${xmlEscape(it)}</h1>\n" }
                .orEmpty()
        val paragraphs = paragraphsOf(chapter.body).joinToString("\n") { "<p>${xmlEscape(it)}</p>" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
            <head>
              <title>${xmlEscape(chapterLabel(chapter))}</title>
              <link rel="stylesheet" type="text/css" href="style.css"/>
            </head>
            <body>
            $heading$paragraphs
            </body>
            </html>
            """
            .trimIndent()
    }

    private fun navXhtml(chapters: List<TxtChapter>, lang: String): String {
        val items = chapters.mapIndexed { index, chapter ->
            """<li><a href="${chapterHref(index)}">${xmlEscape(chapterLabel(chapter))}</a></li>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
            <head><title>目录</title></head>
            <body>
            <nav epub:type="toc" id="toc">
            <h1>目录</h1>
            <ol>
            ${items.joinToString("\n")}
            </ol>
            </nav>
            </body>
            </html>
            """
            .trimIndent()
    }

    private fun tocNcx(
        chapters: List<TxtChapter>,
        title: String,
        bookId: UUID,
        lang: String,
    ): String {
        val points = chapters.mapIndexed { index, chapter ->
            val order = index + 1
            """
                <navPoint id="nav-$order" playOrder="$order">
                  <navLabel><text>${xmlEscape(chapterLabel(chapter))}</text></navLabel>
                  <content src="${chapterHref(index)}"/>
                </navPoint>
                """
                .trimIndent()
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="$lang">
            <head><meta name="dtb:uid" content="urn:uuid:$bookId"/></head>
            <docTitle><text>${xmlEscape(title)}</text></docTitle>
            <navMap>
            ${points.joinToString("\n")}
            </navMap>
            </ncx>
            """
            .trimIndent()
    }

    private fun contentOpf(
        chapters: List<TxtChapter>,
        title: String,
        bookId: UUID,
        lang: String,
    ): String {
        val manifest = chapters.mapIndexed { index, _ ->
            val order = index + 1
            """<item id="chapter-$order" href="${chapterHref(index)}" """ +
                """media-type="application/xhtml+xml"/>"""
        }
        val spine = chapters.indices.map { index -> """<itemref idref="chapter-${index + 1}"/>""" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="bookid" xml:lang="$lang">
            <metadata>
              <dc:identifier id="bookid">urn:uuid:$bookId</dc:identifier>
              <dc:title>${xmlEscape(title)}</dc:title>
              <dc:language>$lang</dc:language>
              <meta property="dcterms:modified">$EPUB_MODIFIED</meta>
            </metadata>
            <manifest>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              <item id="css" href="style.css" media-type="text/css"/>
            ${manifest.joinToString("\n")}
            </manifest>
            <spine toc="ncx">
            ${spine.joinToString("\n")}
            </spine>
            </package>
            """
            .trimIndent()
    }

    private fun detectLanguage(chapters: List<TxtChapter>): String {
        for (chapter in chapters) {
            if (chapter.body.any { it.isCjk() }) return "zh"
            if (chapter.title?.any { it.isCjk() } == true) return "zh"
        }
        return "en"
    }

    private fun Char.isCjk(): Boolean = this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'

    private fun paragraphsOf(body: String): List<String> {
        val paragraphs = mutableListOf<String>()
        body.lineSequence().forEach { line ->
            val text = line.trim()
            if (text.isEmpty()) return@forEach
            if (text.length <= MAX_PARAGRAPH_CHARS) {
                paragraphs += text
            } else {
                paragraphs += splitLongParagraph(text)
            }
        }
        return paragraphs
    }

    /** 超长行按句读切到 [PARAGRAPH_TARGET_CHARS] 附近；没有标点就按 [MAX_PARAGRAPH_CHARS] 硬切。 */
    private fun splitLongParagraph(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        var cursor = 0
        while (cursor < text.length) {
            val length = cursor - start + 1
            val atSentenceEnd = text[cursor] in "。！？…!?"
            if (
                (atSentenceEnd && length >= PARAGRAPH_TARGET_CHARS) || length >= MAX_PARAGRAPH_CHARS
            ) {
                chunks += text.substring(start, cursor + 1).trim()
                start = cursor + 1
            }
            cursor++
        }
        if (start < text.length) chunks += text.substring(start).trim()
        return chunks.filter { it.isNotEmpty() }
    }

    private fun xmlEscape(value: String): String {
        val cleaned = value.filterNot { it < ' ' && it != '\t' && it != '\n' && it != '\r' }
        return cleaned
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
                time = ZIP_ENTRY_TIME_MS
            }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private const val STYLE_CSS =
        "body { margin: 0; padding: 0; }\n" +
            "p { margin: 0 0 0.55em 0; }\n" +
            "h1 { font-size: 1.2em; font-weight: 600; margin: 1.1em 0 0.9em 0; text-align: center; }\n"
}
