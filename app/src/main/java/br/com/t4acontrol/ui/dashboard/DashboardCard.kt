package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun DashboardCard(surface: Color, outline: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(T4ADashboardTokens.CardElevation, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .background(surface, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .border(1.dp, outline, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .padding(T4ADashboardTokens.CardPadding),
        content = content,
    )
}
