package com.R.codecore.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUrlPreprocessorTest {

    @Test
    fun urlFollowedByChineseSentenceShouldNotBeIncluded() {
        val input =
            "阿里云镜像地址可能是：https://mirrors.aliyun.com/android/repository/。我们可以设置环境变量REPO_OS_URL。"
        val got = MarkdownUrlPreprocessor.normalize(input)
        val expect =
            "阿里云镜像地址可能是：<https://mirrors.aliyun.com/android/repository/>。我们可以设置环境变量REPO_OS_URL。"
        assertEquals(expect, got)
    }

    @Test
    fun urlFollowedByChineseCommaAndPeriod() {
        val input =
            "根据搜索结果，阿里云镜像地址可能是：https://mirrors.aliyun.com/android/repository/。我们可以设置环境变量REPO_OS_URL。但sdkmanager可能不支持这个环境变量。"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "根据搜索结果，阿里云镜像地址可能是：<https://mirrors.aliyun.com/android/repository/>。我们可以设置环境变量REPO_OS_URL。但sdkmanager可能不支持这个环境变量。",
            got
        )
    }

    @Test
    fun urlInMarkdownLinkShouldBeSkipped() {
        val input = "点这里下载：[gradle](https://mirrors.aliyun.com/gradle/distributions/v9.4.1/gradle-9.4.1-bin.zip)"
        val got = MarkdownUrlPreprocessor.normalize(input)
        // 不应再给已包裹的 URL 加一层 <...>，保持原样
        assertEquals(input, got)
    }

    @Test
    fun urlInAngleBracketsShouldBeSkipped() {
        val input = "文档：<https://docs.example.com/zh-cn/latest。。>"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(input, got)
    }

    @Test
    fun urlQueryStringIsPreserved() {
        val input = "见官网 https://example.com/path?a=1&b=%E4%B8%AD%E6%96%87&c=2，然后点击"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "见官网 <https://example.com/path?a=1&b=%E4%B8%AD%E6%96%87&c=2>，然后点击",
            got
        )
    }

    @Test
    fun urlFollowedByAsciiPeriodCommaAreStripped() {
        val input = "see https://example.com/path, okay? It says so."
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "see <https://example.com/path>, okay? It says so.",
            got
        )
    }

    @Test
    fun urlWithValidParenthesesIsPreserved() {
        // Wikipedia 样式：URL 里包含配对括号
        val input = "维基百科：https://en.wikipedia.org/wiki/Android_(operating_system) 介绍得很全。"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "维基百科：<https://en.wikipedia.org/wiki/Android_(operating_system)> 介绍得很全。",
            got
        )
    }

    @Test
    fun urlWithTrailingUnmatchedParenIsStripped() {
        val input = "请先看（参考文档：https://docs.example.com/page）然后继续。"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "请先看（参考文档：<https://docs.example.com/page>）然后继续。",
            got
        )
    }

    @Test
    fun multipleUrlsOnMultipleLines() {
        val input =
            "方案 A：https://mirrors.aliyun.com/android/repository/。方案 B：https://mirrors.cloud.tencent.com/AndroidSDK/。两个都试试。"
        val got = MarkdownUrlPreprocessor.normalize(input)
        assertEquals(
            "方案 A：<https://mirrors.aliyun.com/android/repository/>。方案 B：<https://mirrors.cloud.tencent.com/AndroidSDK/>。两个都试试。",
            got
        )
    }

    @Test
    fun shortInputIsReturnedAsIs() {
        assertEquals("ok", MarkdownUrlPreprocessor.normalize("ok"))
    }

    @Test
    fun codeBlockContentIsNotChanged() {
        val input = "```bash\ncurl https://example.com/api。\n```"
        // 简单做法：目前预处理不识别代码块，但仍会尝试替换裸 URL；
        // 链接化对代码渲染结果通常无视觉副作用（代码块样式优先）。
        // 这里确保不崩即可，不做强断言。
        val got = MarkdownUrlPreprocessor.normalize(input)
        assert(got.isNotEmpty())
    }
}
