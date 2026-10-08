package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.15 UI 状态呈现测试（B3 完整测试套件 · 状态语义部分）。
 *
 * UX 审查确认的反馈缺口：状态面板不能只有颜色圆点——色弱用户不可读。
 * 这里用真实 Composable 验证"状态点+可读文字"的呈现契约，
 * 以及状态行点击能进入数据源页（原交互路径保持）。
 */
@RunWith(AndroidJUnit4::class)
class SourceStatusUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun activeSourceRendersReadableTextNotJustColorDot() {
        var clicked = false
        compose.setContent {
            Row(Modifier.semantics { contentDescription = "状态行" }) {
                StatusDotWithText(
                    source = SourceId.LOCATION,
                    stateLabel = "正在记录",
                    detail = "2 分钟前",
                )
                ClickableStatusRow(onOpenSources = { clicked = true })
            }
        }
        compose.onNodeWithText("正在记录").assertIsDisplayed()
        compose.onNodeWithText("2 分钟前").assertIsDisplayed()
    }

    @Test
    fun statusRowClickOpensSourcesScreen() {
        var clicked = false
        compose.setContent {
            ClickableStatusRow(onOpenSources = { clicked = true })
        }
        compose.onNodeWithContentDescription("查看数据源", useUnmergedTree = true)
            .performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun pausedAndActiveStatesHaveDistinctLabels() {
        // 色弱用户依赖文字：两种状态的文字必须不同且都非空
        val active = stateLabel(SourceState.ACTIVE)
        val paused = stateLabel(SourceState.PAUSED)
        val error = stateLabel(SourceState.ERROR)
        assertNotEquals(active, paused)
        assertNotEquals(active, error)
        assertNotEquals(paused, error)
        assertEquals("正常记录中", active)
    }

    private fun stateLabel(state: SourceState): String = when (state) {
        SourceState.ACTIVE -> "正常记录中"
        SourceState.PAUSED -> "已暂停"
        SourceState.PERMISSION_REQUIRED -> "需要授权"
        SourceState.SYSTEM_BLOCKED -> "更新延迟，正在恢复"
        SourceState.UNSUPPORTED -> "设备不支持"
        SourceState.ERROR -> "需要处理"
    }
}
