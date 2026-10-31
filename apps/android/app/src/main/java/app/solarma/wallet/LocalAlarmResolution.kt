package app.solarma.wallet

import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity

/**
 * Local bookkeeping once an alarm's deposit is settled on-chain. One-time alarms are
 * removed (they are already disabled after ringing); repeating alarms keep ringing
 * but lose their (now settled) deposit.
 */
internal suspend fun AlarmDao.resolveDeposit(alarm: AlarmEntity) {
    if (alarm.repeatDays == 0) {
        deleteById(alarm.id)
    } else {
        update(
            alarm.copy(
                hasDeposit = false,
                depositAmount = 0.0,
                depositLamports = 0,
                onchainPubkey = null,
                onchainAlarmId = null,
                onchainDeadline = null,
                snoozeCount = 0,
            ),
        )
    }
}
