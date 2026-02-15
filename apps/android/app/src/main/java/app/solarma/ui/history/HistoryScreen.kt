package app.solarma.ui.history

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.ui.components.SolarmaBackground
import app.solarma.ui.theme.*
import app.solarma.R
import androidx.compose.ui.res.stringResource
import app.solarma.wallet.PendingTransaction
import java.text.SimpleDateFormat
import java.util.*

/**
 * Transaction history screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val transactions by viewModel.transactions.collectAsState()

    SolarmaBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Transaction History",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            if (transactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📊", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.no_transactions_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                                stringResource(R.string.no_transactions_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    items(transactions, key = { it.id }) { tx ->
                        TransactionCard(tx)
                    }

                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
fun TransactionCard(tx: PendingTransaction) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val (iconText, label, color) = when (tx.type) {
        "CREATE_ALARM" -> {
            val title = if (tx.status == "CONFIRMED") "Deposit" else "Deposit Pending"
            Triple("DEP", title, SolanaPurple)
        }
        "CLAIM" -> Triple("CLM", "Claim", SolanaGreen)
        "SNOOZE" -> Triple("SNZ", "Snooze Penalty", WarningAmber)
        "SLASH" -> Triple("SLS", "Slashed", ErrorCrimson)
        "EMERGENCY_REFUND" -> Triple("REF", "Emergency Refund", SolanaPurple)
        else -> Triple("TX", tx.type, TextSecondary)
    }

    val statusColor = when (tx.status) {
        "CONFIRMED" -> SolanaGreen
        "PENDING", "SENDING" -> SolanaPurple
        "FAILED" -> ErrorCrimson
        else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SolarmaShapes.medium,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(SolarmaShapes.small)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
                        )
                    )
                    .border(1.dp, color.copy(alpha = 0.3f), SolarmaShapes.small),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    iconText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = dateFormat.format(Date(tx.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                tx.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCrimson,
                        maxLines = 1
                    )
                }
            }

            // Status chip
            Surface(
                shape = SolarmaShapes.small,
                color = statusColor.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.2f))
            ) {
                Text(
                    text = tx.status,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
