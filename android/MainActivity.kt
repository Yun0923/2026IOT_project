package com.example.real_iot_project
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import android.os.Bundle
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private const val SERVER_URL = "http://10.42.0.1:5000"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                AppMain()
            }
        }
    }
}

data class AlarmData(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val date: String,
    val days: List<String>,
    val name: String,
    val sound: String
) {

    val timeText: String
        get() = formatAlarmTime(hour, minute)

    val daysText: String
        get() = if (days.isEmpty()) "반복 없음" else days.joinToString(", ")
}

data class NextAlarmInfo(
    val alarm: AlarmData,
    val dateTime: LocalDateTime
)

data class AlarmDismissLog(
    val id: Int,
    val alarmName: String,
    val date: String,
    val hour: Int,
    val minute: Int,
    val amountMl: Int = 0
) {
    val timeText: String
        get() = formatAlarmTime(hour, minute)
}

data class AlarmServerStatus(
    val result: String,
    val amountMl: Int
)

@Composable
fun AppMain() {
    var currentScreen by remember { mutableStateOf("start") }
    var editingAlarm by remember { mutableStateOf<AlarmData?>(null) }
    var nextAlarmId by remember { mutableStateOf(4) }

    val alarms = remember {
        mutableStateListOf(
            AlarmData(
                id = 1,
                hour = 7,
                minute = 0,
                date = "2026-5-04",
                days = listOf("월", "화", "수"),
                name = "등교알람",
                sound = "기본 알람음"
            ),
            AlarmData(
                id = 2,
                hour = 18,
                minute = 0,
                date = "2026-5-04",
                days = listOf("수"),
                name = "알림1",
                sound = "기본 알람음"
            ),
            AlarmData(
                id = 3,
                hour = 9,
                minute = 0,
                date = "2026-5-04",
                days = listOf("토", "일"),
                name = "알림2",
                sound = "기본 알람음"
            )
        )
    }

    val alarmLogs = remember {
        mutableStateListOf<AlarmDismissLog>()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var alarmPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isAppAlarmRunning by remember { mutableStateOf(false) }
    var alarmBlockUntil by remember { mutableStateOf<LocalDateTime?>(null) }

    fun refreshAlarmLogsFromServer() {
        coroutineScope.launch {
            try {
                val serverLogs = fetchDrinkRecordsFromServer()
                alarmLogs.clear()
                alarmLogs.addAll(serverLogs)
            } catch (e: Exception) {
                println("기록 불러오기 실패: ${e.message}")
            }
        }
    }

    fun stopAppAlarmSound() {
        try {
            alarmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            println("알람음 정지 실패: ${e.message}")
        }

        alarmPlayer = null
        isAppAlarmRunning = false
    }

    fun startDrinkAlarmFlow(scheduledTime: String) {
        if (isAppAlarmRunning) {
            return
        }

        isAppAlarmRunning = true
        alarmBlockUntil = LocalDateTime.now()
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)

        try {
            alarmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            alarmPlayer = playAlarmSound(context)
        } catch (e: Exception) {
            println("알람음 시작 실패: ${e.message}")
        }

        coroutineScope.launch {
            try {
                syncServerTimeWithPhone()

                val started = requestAlarmStart(scheduledTime)

                if (!started) {
                    stopAppAlarmSound()
                    return@launch
                }

                while (isAppAlarmRunning) {
                    delay(1000L)

                    val status = fetchAlarmStatusFromServer()

                    if (status.result == "SUCCESS") {
                        stopAppAlarmSound()

                        val serverLogs = fetchDrinkRecordsFromServer()
                        alarmLogs.clear()
                        alarmLogs.addAll(serverLogs)

                        break
                    }
                }
            } catch (e: Exception) {
                println("알람 처리 실패: ${e.message}")
                stopAppAlarmSound()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            syncServerTimeWithPhone()

            val serverLogs = fetchDrinkRecordsFromServer()
            alarmLogs.clear()
            alarmLogs.addAll(serverLogs)
        } catch (e: Exception) {
            println("초기 기록 불러오기 실패: ${e.message}")
        }
    }

    BackHandler(
        enabled = currentScreen == "main" || currentScreen == "create" || currentScreen == "record"
    ) {
        when (currentScreen) {
            "create" -> {
                editingAlarm = null
                currentScreen = "main"
            }

            "record" -> {
                currentScreen = "main"
            }

            "main" -> {
                currentScreen = "start"
            }
        }
    }

    when (currentScreen) {
        "start" -> StartScreen(
            onStartClick = {
                currentScreen = "main"
            }
        )

        "main" -> MainScreen(
            alarms = alarms,
            alarmBlockUntil = alarmBlockUntil,
            onAddClick = {
                editingAlarm = null
                currentScreen = "create"
            },
            onAlarmClick = { alarm ->
                editingAlarm = alarm
                currentScreen = "create"
            },
            onRecordClick = {
                refreshAlarmLogsFromServer()
                currentScreen = "record"
            },
            onImmediateAlarmClick = {
                startDrinkAlarmFlow("APP_NOW")
            },
            onAlarmTimeReached = { alarm ->
                startDrinkAlarmFlow("${alarm.name} ${alarm.timeText}")
            }
        )

        "create" -> CreateAlarmScreen(
            editAlarm = editingAlarm,
            newAlarmId = nextAlarmId,
            onCancelClick = {
                editingAlarm = null
                currentScreen = "main"
            },
            onSaveClick = { savedAlarm ->
                val index = alarms.indexOfFirst { it.id == savedAlarm.id }

                if (index >= 0) {
                    alarms[index] = savedAlarm
                } else {
                    alarms.add(savedAlarm)
                    nextAlarmId++
                }

                editingAlarm = null
                currentScreen = "main"
            }
        )

        "record" -> RecordScreen(
            logs = alarmLogs,
            onBackClick = {
                currentScreen = "main"
            }
        )
    }
}

@Composable
fun StartScreen(
    onStartClick: () -> Unit
) {
    val backgroundColor = Color(0xFF5588D0)
    val outerCircleColor = Color(0xFFD7E5F5)
    val buttonColor = Color(0xFFF3F8FC)
    val buttonBorderColor = Color(0xFF2F3C48)
    val buttonTextColor = Color(0xFF4D6F9A)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 170.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(outerCircleColor),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        CupIcon(
                            modifier = Modifier.size(82.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Smart Water Alarm",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )

                Text(
                    text = "Build a healthy routine",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .width(280.dp)
                    .height(52.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .clip(RoundedCornerShape(15.dp))
                    .background(buttonColor)
                    .border(
                        width = 2.dp,
                        color = buttonBorderColor,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .clickable {
                        onStartClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start",
                    color = buttonTextColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    alarms: MutableList<AlarmData>,
    alarmBlockUntil: LocalDateTime?,
    onAddClick: () -> Unit,
    onAlarmClick: (AlarmData) -> Unit,
    onRecordClick: () -> Unit,
    onImmediateAlarmClick: () -> Unit,
    onAlarmTimeReached: (AlarmData) -> Unit
){
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val triggeredAlarmKeys = remember { mutableStateListOf<String>() }
    val currentAlarmBlockUntil by rememberUpdatedState(alarmBlockUntil)

    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current

            val blockUntil = currentAlarmBlockUntil
            val isBlocked = blockUntil != null && current.isBefore(blockUntil)

            if (!isBlocked) {
                val dueAlarm = alarms.firstOrNull { alarm ->
                    isAlarmDueNow(alarm, current)
                }

                if (dueAlarm != null) {
                    val triggerKey = "${dueAlarm.id}-${current.toLocalDate()}-${current.hour}-${current.minute}"

                    if (!triggeredAlarmKeys.contains(triggerKey)) {
                        triggeredAlarmKeys.add(triggerKey)
                        onAlarmTimeReached(dueAlarm)
                    }
                }
            }

            delay(1000L)
        }
    }

    val nextAlarmInfo = findNextAlarm(
        alarms = alarms,
        now = now
    )

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val blueText = Color(0xFF4974FF)
    val grayText = Color(0xFF9BA5B2)
    val darkText = Color(0xFF333333)
    val yellowText = Color(0xFFE6C46D)
    val recordText = Color(0xFFD8E5FF)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Smart Water Alarm",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "",
                    color = yellowText,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clickable {
                            onImmediateAlarmClick()
                        },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 6.dp
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "다음 알림",
                            color = grayText,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = nextAlarmInfo?.alarm?.timeText ?: "--:--",
                            color = blueText,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (nextAlarmInfo == null) {
                                "설정된 알람 없음"
                            } else {
                                "${formatRemainingTime(now, nextAlarmInfo.dateTime)} 후"
                            },
                            color = grayText,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "내 알람",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "+",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            onAddClick()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                alarms.toList().forEach { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        blueText = blueText,
                        darkText = darkText,
                        grayText = grayText,
                        cardColor = cardColor,
                        onClick = {
                            onAlarmClick(alarm)
                        },
                        onDelete = {
                            alarms.remove(alarm)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Text(
                text = "Record>",
                color = recordText,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 18.dp, end = 4.dp)
                    .clickable {
                        onRecordClick()
                    }
            )
        }
    }
}

@Composable
fun AlarmCard(
    alarm: AlarmData,
    blueText: Color,
    darkText: Color,
    grayText: Color,
    cardColor: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clickable {
                onClick()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(88.dp)
            ) {
                Text(
                    text = alarm.timeText,
                    color = blueText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = alarm.daysText,
                    color = darkText,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = alarm.name,
                color = darkText,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "삭제",
                color = grayText,
                fontSize = 10.sp,
                modifier = Modifier.clickable {
                    onDelete()
                }
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAlarmScreen(
    editAlarm: AlarmData?,
    newAlarmId: Int,
    onCancelClick: () -> Unit,
    onSaveClick: (AlarmData) -> Unit
) {
    var alarmHour by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.hour ?: 7)
    }

    var alarmMinute by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.minute ?: 0)
    }

    var alarmDate by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.date ?: formatLocalDate(LocalDate.now()))
    }

    var alarmName by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.name ?: "")
    }

    var alarmSound by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.sound ?: "기본 알람음")
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val selectedDays = remember(editAlarm?.id) {
        mutableStateListOf<String>().apply {
            addAll(editAlarm?.days ?: emptyList())
        }
    }

    val timePickerState = rememberTimePickerState(
        initialHour = alarmHour,
        initialMinute = alarmMinute,
        is24Hour = false
    )

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateTextToMillis(alarmDate)
    )

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val blueText = Color(0xFF4974FF)
    val borderColor = Color(0xFF222222)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(38.dp))

                Text(
                    text = if (editAlarm == null) "Create Alarm" else "Edit Alarm",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(20.dp))

                CreateInputCard(
                    label = "알람 시간",
                    cardColor = cardColor
                ) {
                    PickerDisplayBox(
                        value = formatAlarmTime(alarmHour, alarmMinute),
                        textColor = blueText,
                        fontSize = 23.sp,
                        height = 42,
                        onClick = {
                            showTimePicker = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                CreateInputCard(
                    label = "알람 날짜",
                    cardColor = cardColor
                ) {
                    PickerDisplayBox(
                        value = alarmDate,
                        textColor = blueText,
                        fontSize = 23.sp,
                        height = 42,
                        onClick = {
                            showDatePicker = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                CreateInputCard(
                    label = "반복 요일",
                    cardColor = cardColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("월", "화", "수", "목", "금", "토", "일").forEach { day ->
                            DaySelectButton(
                                day = day,
                                selected = selectedDays.contains(day),
                                onClick = {
                                    if (selectedDays.contains(day)) {
                                        selectedDays.remove(day)
                                    } else {
                                        selectedDays.add(day)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                RowTextInputCard(
                    label = "알람이름",
                    value = alarmName,
                    placeholder = "알람 이름 입력",
                    cardColor = cardColor,
                    onValueChange = {
                        alarmName = it
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SoundSelectCard(
                    label = "알람소리",
                    soundName = alarmSound,
                    cardColor = cardColor,
                    onClick = {
                        alarmSound = "기본 알람음"
                    }
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BottomActionButton(
                    text = "Cancel",
                    textColor = blueText,
                    borderColor = borderColor,
                    onClick = {
                        onCancelClick()
                    }
                )

                BottomActionButton(
                    text = "Save",
                    textColor = blueText,
                    borderColor = borderColor,
                    onClick = {
                        val orderedDays = listOf("월", "화", "수", "목", "금", "토", "일")
                            .filter { selectedDays.contains(it) }

                        val nameText = if (alarmName.isBlank()) {
                            "새 알람"
                        } else {
                            alarmName
                        }

                        val savedAlarm = AlarmData(
                            id = editAlarm?.id ?: newAlarmId,
                            hour = alarmHour,
                            minute = alarmMinute,
                            date = alarmDate,
                            days = orderedDays,
                            name = nameText,
                            sound = alarmSound
                        )

                        onSaveClick(savedAlarm)
                    }
                )
            }
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = {
                showTimePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarmHour = timePickerState.hour
                        alarmMinute = timePickerState.minute
                        showTimePicker = false
                    }
                ) {
                    Text(text = "확인")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                    }
                ) {
                    Text(text = "취소")
                }
            },
            title = {
                Text(text = "알람 시간 선택")
            },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(
                        state = timePickerState
                    )
                }
            }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis

                        if (selectedMillis != null) {
                            alarmDate = formatAlarmDate(selectedMillis)
                        }

                        showDatePicker = false
                    }
                ) {
                    Text(text = "확인")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text(text = "취소")
                }
            }
        ) {
            DatePicker(
                state = datePickerState
            )
        }
    }
}

@Composable
fun CreateInputCard(
    label: String,
    cardColor: Color,
    height: Int = 78,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            content()
        }
    }
}

@Composable
fun PickerDisplayBox(
    value: String,
    textColor: Color,
    fontSize: TextUnit,
    height: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                width = 1.dp,
                color = Color(0xFFC4C4C4),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = value,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
fun RowTextInputCard(
    label: String,
    value: String,
    placeholder: String,
    cardColor: Color,
    onValueChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(28.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFFB8BEC7),
                        fontSize = 13.sp
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFF333333),
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SoundSelectCard(
    label: String,
    soundName: String,
    cardColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable {
                onClick()
            },
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(28.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(
                        width = 1.dp,
                        color = Color(0xFFC4C4C4),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = soundName,
                    color = Color(0xFF333333),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = ">",
                color = Color(0xFF9BA5B2),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun DaySelectButton(
    day: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) {
        Color(0xFF4974FF)
    } else {
        Color.White
    }

    val textColor = if (selected) {
        Color.White
    } else {
        Color(0xFF333333)
    }

    Box(
        modifier = Modifier
            .width(34.dp)
            .height(27.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = Color(0xFFD1D1D1),
                shape = RoundedCornerShape(7.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day,
            color = textColor,
            fontSize = 11.sp
        )
    }
}

@Composable
fun BottomActionButton(
    text: String,
    textColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(128.dp)
            .height(36.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F8FC))
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
@Composable
fun RecordScreen(
    logs: List<AlarmDismissLog>,
    onBackClick: () -> Unit
) {
    BackHandler {
        onBackClick()
    }

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val grayText = Color(0xFF9BA5B2)
    val darkText = Color(0xFF333333)
    val blueText = Color(0xFF4974FF)

    val chartLogs = logs
        .sortedBy { getDismissLogDateTime(it) }
        .takeLast(7)

    val logList = logs
        .sortedByDescending { getDismissLogDateTime(it) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(38.dp))

            Text(
                text = "Record",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 6.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Week Summary",
                        color = grayText,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    DismissTimeChart(
                        logs = chartLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(122.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Alarm Log",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (logList.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 5.dp
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "알람 해제 기록이 없습니다.",
                            color = grayText,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                logList.forEach { log ->
                    AlarmLogCard(
                        log = log,
                        cardColor = cardColor,
                        blueText = blueText,
                        darkText = darkText,
                        grayText = grayText
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun DismissTimeChart(
    logs: List<AlarmDismissLog>,
    modifier: Modifier = Modifier
) {
    val minMinutes = 6 * 60f
    val maxMinutes = 10 * 60f
    val rangeMinutes = maxMinutes - minMinutes

    if (logs.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "알람 해제 기록 없음",
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )
        }

        return
    }

    Column(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                listOf("10시", "9시", "8시", "7시", "6시").forEach { label ->
                    Text(
                        text = label,
                        color = Color(0xFF9BA5B2),
                        fontSize = 8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 2.dp)
            ) {
                val gridColor = Color(0xFFD8DEE4)
                val axisColor = Color(0xFFB8C0CC)
                val lineColor = Color(0xFF4974FF)

                for (i in 0..4) {
                    val y = size.height * (i / 4f)

                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.2f
                    )
                }

                drawLine(
                    color = axisColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.5f
                )

                drawLine(
                    color = axisColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.5f
                )

                val points = logs.mapIndexed { index, log ->
                    val minuteValue = (log.hour * 60 + log.minute)
                        .toFloat()
                        .coerceIn(minMinutes, maxMinutes)

                    val x = if (logs.size == 1) {
                        size.width / 2f
                    } else {
                        size.width / (logs.size - 1) * index
                    }

                    val yRatio = (minuteValue - minMinutes) / rangeMinutes
                    val y = size.height - yRatio * size.height

                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)

                        points.drop(1).forEach { point ->
                            lineTo(point.x, point.y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(
                            width = 3f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                points.forEach { point ->
                    drawCircle(
                        color = lineColor,
                        radius = 5f,
                        center = point
                    )

                    drawCircle(
                        color = Color.White,
                        radius = 2.5f,
                        center = point
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.width(32.dp))

            Row(
                modifier = Modifier.weight(1f)
            ) {
                logs.forEach { log ->
                    Text(
                        text = formatChartDate(log.date),
                        color = Color(0xFF9BA5B2),
                        fontSize = 7.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmLogCard(
    log: AlarmDismissLog,
    cardColor: Color,
    blueText: Color,
    darkText: Color,
    grayText: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = log.date,
                    color = grayText,
                    fontSize = 10.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = log.alarmName,
                    color = darkText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = log.timeText,
                    color = blueText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (log.amountMl > 0) "${log.amountMl}ml 섭취" else "해제 완료",
                    color = grayText,
                    fontSize = 9.sp
                )
            }
        }
    }
}

fun playAlarmSound(context: Context): MediaPlayer? {
    val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ?: return null

    return try {
        MediaPlayer().apply {
            setDataSource(context, ringtoneUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = true
            setOnPreparedListener { player ->
                player.start()
            }
            prepareAsync()
        }
    } catch (e: Exception) {
        println("알람음 재생 실패: ${e.message}")
        null
    }
}

suspend fun fetchDrinkRecordsFromServer(): List<AlarmDismissLog> {
    return withContext(Dispatchers.IO) {
        val body = httpGet("$SERVER_URL/records")
        val jsonArray = JSONArray(body)
        val logs = mutableListOf<AlarmDismissLog>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)

            val completedAt = item.optString("completed_at", "")
            val completedDateTime = parseServerDateTime(completedAt) ?: continue

            val result = item.optString("result", "")
            if (result != "SUCCESS") {
                continue
            }

            val amountMl = item.optString("amount_ml", "0").toIntOrNull() ?: 0
            val scheduledTime = item.optString("scheduled_time", "물 섭취 알람")

            logs.add(
                AlarmDismissLog(
                    id = i + 1,
                    alarmName = if (scheduledTime.isBlank()) "물 섭취 알람" else scheduledTime,
                    date = formatLocalDate(completedDateTime.toLocalDate()),
                    hour = completedDateTime.hour,
                    minute = completedDateTime.minute,
                    amountMl = amountMl
                )
            )
        }

        logs
    }
}

suspend fun syncServerTimeWithPhone() {
    withContext(Dispatchers.IO) {
        val phoneTimeMs = System.currentTimeMillis()

        val requestBody = JSONObject()
            .put("client_time_ms", phoneTimeMs)
            .toString()

        httpPostJson("$SERVER_URL/time/sync", requestBody)
    }
}

suspend fun requestAlarmStart(scheduledTime: String): Boolean {
    return withContext(Dispatchers.IO) {
        val phoneTimeMs = System.currentTimeMillis()

        val requestBody = JSONObject()
            .put("scheduled_time", scheduledTime)
            .put("client_time_ms", phoneTimeMs)
            .toString()

        val body = httpPostJson("$SERVER_URL/alarm/start", requestBody)
        val jsonObject = JSONObject(body)
        val status = jsonObject.optString("status", "")

        status == "started" || status == "already_running"
    }
}

suspend fun fetchAlarmStatusFromServer(): AlarmServerStatus {
    return withContext(Dispatchers.IO) {
        val body = httpGet("$SERVER_URL/alarm/status")
        val jsonObject = JSONObject(body)

        AlarmServerStatus(
            result = jsonObject.optString("result", "UNKNOWN"),
            amountMl = jsonObject.optInt("amount_ml", 0)
        )
    }
}

fun httpGet(urlText: String): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection

    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw Exception("HTTP $responseCode: $body")
        }

        body
    } finally {
        connection.disconnect()
    }
}

fun httpPostJson(urlText: String, jsonBody: String): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection

    return try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        connection.outputStream.use { output ->
            output.write(jsonBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw Exception("HTTP $responseCode: $body")
        }

        body
    } finally {
        connection.disconnect()
    }
}

fun parseServerDateTime(dateTimeText: String): LocalDateTime? {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    return try {
        LocalDateTime.parse(dateTimeText, formatter)
    } catch (e: Exception) {
        null
    }
}

fun isAlarmDueNow(
    alarm: AlarmData,
    now: LocalDateTime
): Boolean {
    val alarmTimeMatches = alarm.hour == now.hour && alarm.minute == now.minute

    if (!alarmTimeMatches) {
        return false
    }

    val baseDate = parseAlarmDate(alarm.date) ?: now.toLocalDate()

    if (alarm.days.isEmpty()) {
        return baseDate == now.toLocalDate()
    }

    if (now.toLocalDate().isBefore(baseDate)) {
        return false
    }

    val todayKorean = when (now.dayOfWeek) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    return alarm.days.contains(todayKorean)
}


fun findNextAlarm(
    alarms: List<AlarmData>,
    now: LocalDateTime
): NextAlarmInfo? {
    return alarms
        .mapNotNull { alarm ->
            val nextTime = getNextAlarmDateTime(
                alarm = alarm,
                now = now
            )

            if (nextTime == null) {
                null
            } else {
                NextAlarmInfo(
                    alarm = alarm,
                    dateTime = nextTime
                )
            }
        }
        .minByOrNull { it.dateTime }
}

fun getNextAlarmDateTime(
    alarm: AlarmData,
    now: LocalDateTime
): LocalDateTime? {
    val baseDate = parseAlarmDate(alarm.date) ?: now.toLocalDate()
    val alarmTime = LocalTime.of(alarm.hour, alarm.minute)

    if (alarm.days.isEmpty()) {
        val candidate = LocalDateTime.of(baseDate, alarmTime)

        return if (candidate.isAfter(now)) {
            candidate
        } else {
            null
        }
    }

    val targetDays = alarm.days
        .mapNotNull { koreanDayToDayOfWeek(it) }
        .toSet()

    if (targetDays.isEmpty()) {
        return null
    }

    val startDate = maxOf(now.toLocalDate(), baseDate)

    for (i in 0..13) {
        val date = startDate.plusDays(i.toLong())
        val candidate = LocalDateTime.of(date, alarmTime)

        if (
            !date.isBefore(baseDate) &&
            candidate.isAfter(now) &&
            targetDays.contains(date.dayOfWeek)
        ) {
            return candidate
        }
    }

    return null
}

fun formatRemainingTime(
    now: LocalDateTime,
    target: LocalDateTime
): String {
    val totalMinutes = ChronoUnit.MINUTES.between(now, target)

    if (totalMinutes <= 0) {
        return "곧 울림"
    }

    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60

    return when {
        days > 0 && hours > 0 -> "${days}일 ${hours}시간"
        days > 0 -> "${days}일"
        hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
        hours > 0 -> "${hours}시간"
        else -> "${minutes}분"
    }
}

fun koreanDayToDayOfWeek(day: String): DayOfWeek? {
    return when (day) {
        "월" -> DayOfWeek.MONDAY
        "화" -> DayOfWeek.TUESDAY
        "수" -> DayOfWeek.WEDNESDAY
        "목" -> DayOfWeek.THURSDAY
        "금" -> DayOfWeek.FRIDAY
        "토" -> DayOfWeek.SATURDAY
        "일" -> DayOfWeek.SUNDAY
        else -> null
    }
}

fun formatAlarmTime(
    hour: Int,
    minute: Int
): String {
    val amPm = if (hour < 12) {
        "AM"
    } else {
        "PM"
    }

    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }

    return String.format(
        Locale.US,
        "%02d:%02d %s",
        hour12,
        minute,
        amPm
    )
}

fun formatAlarmDate(
    millis: Long
): String {
    val date = Instant
        .ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    return formatLocalDate(date)
}

fun formatLocalDate(
    date: LocalDate
): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-M-dd")
    return date.format(formatter)
}

fun parseAlarmDate(
    dateText: String
): LocalDate? {
    return try {
        val formatter = DateTimeFormatter.ofPattern("yyyy-M-dd")
        LocalDate.parse(dateText, formatter)
    } catch (e: Exception) {
        null
    }
}

fun dateTextToMillis(
    dateText: String
): Long {
    val date = parseAlarmDate(dateText) ?: LocalDate.now()

    return date
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

fun getDismissLogDateTime(
    log: AlarmDismissLog
): LocalDateTime {
    val date = parseAlarmDate(log.date) ?: LocalDate.now()
    val time = LocalTime.of(log.hour, log.minute)

    return LocalDateTime.of(date, time)
}

fun formatChartDate(
    dateText: String
): String {
    val date = parseAlarmDate(dateText) ?: return dateText

    return "${date.monthValue}/${String.format(Locale.US, "%02d", date.dayOfMonth)}"
}

@Composable
fun CupIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val outlineColor = Color(0xFF222222)
        val waterColor = Color(0xFF9FD5F4)
        val bubbleColor = Color.White

        val waterPath = Path().apply {
            moveTo(w * 0.31f, h * 0.50f)
            quadraticBezierTo(w * 0.50f, h * 0.43f, w * 0.69f, h * 0.50f)
            lineTo(w * 0.61f, h * 0.78f)
            quadraticBezierTo(w * 0.50f, h * 0.84f, w * 0.39f, h * 0.78f)
            close()
        }

        drawPath(
            path = waterPath,
            color = waterColor
        )

        val wavePath = Path().apply {
            moveTo(w * 0.31f, h * 0.50f)
            quadraticBezierTo(w * 0.50f, h * 0.43f, w * 0.69f, h * 0.50f)
        }

        drawPath(
            path = wavePath,
            color = Color.White,
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.025f,
            center = Offset(w * 0.42f, h * 0.62f)
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.018f,
            center = Offset(w * 0.55f, h * 0.58f)
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.015f,
            center = Offset(w * 0.50f, h * 0.70f)
        )

        val cupSidePath = Path().apply {
            moveTo(w * 0.24f, h * 0.18f)
            lineTo(w * 0.36f, h * 0.86f)
            quadraticBezierTo(w * 0.50f, h * 0.94f, w * 0.64f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.18f)
        }

        drawPath(
            path = cupSidePath,
            color = outlineColor,
            style = Stroke(
                width = 3.2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawOval(
            color = outlineColor,
            topLeft = Offset(w * 0.24f, h * 0.11f),
            size = Size(w * 0.52f, h * 0.14f),
            style = Stroke(
                width = 3.2f,
                cap = StrokeCap.Round
            )
        )
    }
}
package com.example.real_iot_project
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import android.os.Bundle
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private const val SERVER_URL = "http://10.42.0.1:5000"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                AppMain()
            }
        }
    }
}

data class AlarmData(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val date: String,
    val days: List<String>,
    val name: String,
    val sound: String
) {

    val timeText: String
        get() = formatAlarmTime(hour, minute)

    val daysText: String
        get() = if (days.isEmpty()) "반복 없음" else days.joinToString(", ")
}

data class NextAlarmInfo(
    val alarm: AlarmData,
    val dateTime: LocalDateTime
)

data class AlarmDismissLog(
    val id: Int,
    val alarmName: String,
    val date: String,
    val hour: Int,
    val minute: Int,
    val amountMl: Int = 0
) {
    val timeText: String
        get() = formatAlarmTime(hour, minute)
}

data class AlarmServerStatus(
    val result: String,
    val amountMl: Int
)

@Composable
fun AppMain() {
    var currentScreen by remember { mutableStateOf("start") }
    var editingAlarm by remember { mutableStateOf<AlarmData?>(null) }
    var nextAlarmId by remember { mutableStateOf(4) }

    val alarms = remember {
        mutableStateListOf(
            AlarmData(
                id = 1,
                hour = 7,
                minute = 0,
                date = "2026-5-04",
                days = listOf("월", "화", "수"),
                name = "등교알람",
                sound = "기본 알람음"
            ),
            AlarmData(
                id = 2,
                hour = 18,
                minute = 0,
                date = "2026-5-04",
                days = listOf("수"),
                name = "알림1",
                sound = "기본 알람음"
            ),
            AlarmData(
                id = 3,
                hour = 9,
                minute = 0,
                date = "2026-5-04",
                days = listOf("토", "일"),
                name = "알림2",
                sound = "기본 알람음"
            )
        )
    }

    val alarmLogs = remember {
        mutableStateListOf<AlarmDismissLog>()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var alarmPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isAppAlarmRunning by remember { mutableStateOf(false) }
    var alarmBlockUntil by remember { mutableStateOf<LocalDateTime?>(null) }

    fun refreshAlarmLogsFromServer() {
        coroutineScope.launch {
            try {
                val serverLogs = fetchDrinkRecordsFromServer()
                alarmLogs.clear()
                alarmLogs.addAll(serverLogs)
            } catch (e: Exception) {
                println("기록 불러오기 실패: ${e.message}")
            }
        }
    }

    fun stopAppAlarmSound() {
        try {
            alarmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            println("알람음 정지 실패: ${e.message}")
        }

        alarmPlayer = null
        isAppAlarmRunning = false
    }

    fun startDrinkAlarmFlow(scheduledTime: String) {
        if (isAppAlarmRunning) {
            return
        }

        isAppAlarmRunning = true
        alarmBlockUntil = LocalDateTime.now()
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)

        try {
            alarmPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            alarmPlayer = playAlarmSound(context)
        } catch (e: Exception) {
            println("알람음 시작 실패: ${e.message}")
        }

        coroutineScope.launch {
            try {
                syncServerTimeWithPhone()

                val started = requestAlarmStart(scheduledTime)

                if (!started) {
                    stopAppAlarmSound()
                    return@launch
                }

                while (isAppAlarmRunning) {
                    delay(1000L)

                    val status = fetchAlarmStatusFromServer()

                    if (status.result == "SUCCESS") {
                        stopAppAlarmSound()

                        val serverLogs = fetchDrinkRecordsFromServer()
                        alarmLogs.clear()
                        alarmLogs.addAll(serverLogs)

                        break
                    }
                }
            } catch (e: Exception) {
                println("알람 처리 실패: ${e.message}")
                stopAppAlarmSound()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            syncServerTimeWithPhone()

            val serverLogs = fetchDrinkRecordsFromServer()
            alarmLogs.clear()
            alarmLogs.addAll(serverLogs)
        } catch (e: Exception) {
            println("초기 기록 불러오기 실패: ${e.message}")
        }
    }

    BackHandler(
        enabled = currentScreen == "main" || currentScreen == "create" || currentScreen == "record"
    ) {
        when (currentScreen) {
            "create" -> {
                editingAlarm = null
                currentScreen = "main"
            }

            "record" -> {
                currentScreen = "main"
            }

            "main" -> {
                currentScreen = "start"
            }
        }
    }

    when (currentScreen) {
        "start" -> StartScreen(
            onStartClick = {
                currentScreen = "main"
            }
        )

        "main" -> MainScreen(
            alarms = alarms,
            alarmBlockUntil = alarmBlockUntil,
            onAddClick = {
                editingAlarm = null
                currentScreen = "create"
            },
            onAlarmClick = { alarm ->
                editingAlarm = alarm
                currentScreen = "create"
            },
            onRecordClick = {
                refreshAlarmLogsFromServer()
                currentScreen = "record"
            },
            onImmediateAlarmClick = {
                startDrinkAlarmFlow("APP_NOW")
            },
            onAlarmTimeReached = { alarm ->
                startDrinkAlarmFlow("${alarm.name} ${alarm.timeText}")
            }
        )

        "create" -> CreateAlarmScreen(
            editAlarm = editingAlarm,
            newAlarmId = nextAlarmId,
            onCancelClick = {
                editingAlarm = null
                currentScreen = "main"
            },
            onSaveClick = { savedAlarm ->
                val index = alarms.indexOfFirst { it.id == savedAlarm.id }

                if (index >= 0) {
                    alarms[index] = savedAlarm
                } else {
                    alarms.add(savedAlarm)
                    nextAlarmId++
                }

                editingAlarm = null
                currentScreen = "main"
            }
        )

        "record" -> RecordScreen(
            logs = alarmLogs,
            onBackClick = {
                currentScreen = "main"
            }
        )
    }
}

@Composable
fun StartScreen(
    onStartClick: () -> Unit
) {
    val backgroundColor = Color(0xFF5588D0)
    val outerCircleColor = Color(0xFFD7E5F5)
    val buttonColor = Color(0xFFF3F8FC)
    val buttonBorderColor = Color(0xFF2F3C48)
    val buttonTextColor = Color(0xFF4D6F9A)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 170.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(outerCircleColor),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        CupIcon(
                            modifier = Modifier.size(82.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Smart Water Alarm",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )

                Text(
                    text = "Build a healthy routine",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .width(280.dp)
                    .height(52.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .clip(RoundedCornerShape(15.dp))
                    .background(buttonColor)
                    .border(
                        width = 2.dp,
                        color = buttonBorderColor,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .clickable {
                        onStartClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start",
                    color = buttonTextColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    alarms: MutableList<AlarmData>,
    alarmBlockUntil: LocalDateTime?,
    onAddClick: () -> Unit,
    onAlarmClick: (AlarmData) -> Unit,
    onRecordClick: () -> Unit,
    onImmediateAlarmClick: () -> Unit,
    onAlarmTimeReached: (AlarmData) -> Unit
){
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val triggeredAlarmKeys = remember { mutableStateListOf<String>() }
    val currentAlarmBlockUntil by rememberUpdatedState(alarmBlockUntil)

    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current

            val blockUntil = currentAlarmBlockUntil
            val isBlocked = blockUntil != null && current.isBefore(blockUntil)

            if (!isBlocked) {
                val dueAlarm = alarms.firstOrNull { alarm ->
                    isAlarmDueNow(alarm, current)
                }

                if (dueAlarm != null) {
                    val triggerKey = "${dueAlarm.id}-${current.toLocalDate()}-${current.hour}-${current.minute}"

                    if (!triggeredAlarmKeys.contains(triggerKey)) {
                        triggeredAlarmKeys.add(triggerKey)
                        onAlarmTimeReached(dueAlarm)
                    }
                }
            }

            delay(1000L)
        }
    }

    val nextAlarmInfo = findNextAlarm(
        alarms = alarms,
        now = now
    )

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val blueText = Color(0xFF4974FF)
    val grayText = Color(0xFF9BA5B2)
    val darkText = Color(0xFF333333)
    val yellowText = Color(0xFFE6C46D)
    val recordText = Color(0xFFD8E5FF)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Smart Water Alarm",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "",
                    color = yellowText,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clickable {
                            onImmediateAlarmClick()
                        },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 6.dp
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "다음 알림",
                            color = grayText,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = nextAlarmInfo?.alarm?.timeText ?: "--:--",
                            color = blueText,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (nextAlarmInfo == null) {
                                "설정된 알람 없음"
                            } else {
                                "${formatRemainingTime(now, nextAlarmInfo.dateTime)} 후"
                            },
                            color = grayText,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "내 알람",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "+",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            onAddClick()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                alarms.toList().forEach { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        blueText = blueText,
                        darkText = darkText,
                        grayText = grayText,
                        cardColor = cardColor,
                        onClick = {
                            onAlarmClick(alarm)
                        },
                        onDelete = {
                            alarms.remove(alarm)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Text(
                text = "Record>",
                color = recordText,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 18.dp, end = 4.dp)
                    .clickable {
                        onRecordClick()
                    }
            )
        }
    }
}

@Composable
fun AlarmCard(
    alarm: AlarmData,
    blueText: Color,
    darkText: Color,
    grayText: Color,
    cardColor: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clickable {
                onClick()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(88.dp)
            ) {
                Text(
                    text = alarm.timeText,
                    color = blueText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = alarm.daysText,
                    color = darkText,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = alarm.name,
                color = darkText,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "삭제",
                color = grayText,
                fontSize = 10.sp,
                modifier = Modifier.clickable {
                    onDelete()
                }
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAlarmScreen(
    editAlarm: AlarmData?,
    newAlarmId: Int,
    onCancelClick: () -> Unit,
    onSaveClick: (AlarmData) -> Unit
) {
    var alarmHour by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.hour ?: 7)
    }

    var alarmMinute by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.minute ?: 0)
    }

    var alarmDate by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.date ?: formatLocalDate(LocalDate.now()))
    }

    var alarmName by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.name ?: "")
    }

    var alarmSound by remember(editAlarm?.id) {
        mutableStateOf(editAlarm?.sound ?: "기본 알람음")
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val selectedDays = remember(editAlarm?.id) {
        mutableStateListOf<String>().apply {
            addAll(editAlarm?.days ?: emptyList())
        }
    }

    val timePickerState = rememberTimePickerState(
        initialHour = alarmHour,
        initialMinute = alarmMinute,
        is24Hour = false
    )

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateTextToMillis(alarmDate)
    )

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val blueText = Color(0xFF4974FF)
    val borderColor = Color(0xFF222222)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(38.dp))

                Text(
                    text = if (editAlarm == null) "Create Alarm" else "Edit Alarm",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(20.dp))

                CreateInputCard(
                    label = "알람 시간",
                    cardColor = cardColor
                ) {
                    PickerDisplayBox(
                        value = formatAlarmTime(alarmHour, alarmMinute),
                        textColor = blueText,
                        fontSize = 23.sp,
                        height = 42,
                        onClick = {
                            showTimePicker = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                CreateInputCard(
                    label = "알람 날짜",
                    cardColor = cardColor
                ) {
                    PickerDisplayBox(
                        value = alarmDate,
                        textColor = blueText,
                        fontSize = 23.sp,
                        height = 42,
                        onClick = {
                            showDatePicker = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                CreateInputCard(
                    label = "반복 요일",
                    cardColor = cardColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("월", "화", "수", "목", "금", "토", "일").forEach { day ->
                            DaySelectButton(
                                day = day,
                                selected = selectedDays.contains(day),
                                onClick = {
                                    if (selectedDays.contains(day)) {
                                        selectedDays.remove(day)
                                    } else {
                                        selectedDays.add(day)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                RowTextInputCard(
                    label = "알람이름",
                    value = alarmName,
                    placeholder = "알람 이름 입력",
                    cardColor = cardColor,
                    onValueChange = {
                        alarmName = it
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SoundSelectCard(
                    label = "알람소리",
                    soundName = alarmSound,
                    cardColor = cardColor,
                    onClick = {
                        alarmSound = "기본 알람음"
                    }
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BottomActionButton(
                    text = "Cancel",
                    textColor = blueText,
                    borderColor = borderColor,
                    onClick = {
                        onCancelClick()
                    }
                )

                BottomActionButton(
                    text = "Save",
                    textColor = blueText,
                    borderColor = borderColor,
                    onClick = {
                        val orderedDays = listOf("월", "화", "수", "목", "금", "토", "일")
                            .filter { selectedDays.contains(it) }

                        val nameText = if (alarmName.isBlank()) {
                            "새 알람"
                        } else {
                            alarmName
                        }

                        val savedAlarm = AlarmData(
                            id = editAlarm?.id ?: newAlarmId,
                            hour = alarmHour,
                            minute = alarmMinute,
                            date = alarmDate,
                            days = orderedDays,
                            name = nameText,
                            sound = alarmSound
                        )

                        onSaveClick(savedAlarm)
                    }
                )
            }
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = {
                showTimePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarmHour = timePickerState.hour
                        alarmMinute = timePickerState.minute
                        showTimePicker = false
                    }
                ) {
                    Text(text = "확인")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                    }
                ) {
                    Text(text = "취소")
                }
            },
            title = {
                Text(text = "알람 시간 선택")
            },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(
                        state = timePickerState
                    )
                }
            }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis

                        if (selectedMillis != null) {
                            alarmDate = formatAlarmDate(selectedMillis)
                        }

                        showDatePicker = false
                    }
                ) {
                    Text(text = "확인")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text(text = "취소")
                }
            }
        ) {
            DatePicker(
                state = datePickerState
            )
        }
    }
}

@Composable
fun CreateInputCard(
    label: String,
    cardColor: Color,
    height: Int = 78,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            content()
        }
    }
}

@Composable
fun PickerDisplayBox(
    value: String,
    textColor: Color,
    fontSize: TextUnit,
    height: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                width = 1.dp,
                color = Color(0xFFC4C4C4),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = value,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
fun RowTextInputCard(
    label: String,
    value: String,
    placeholder: String,
    cardColor: Color,
    onValueChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(28.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFFB8BEC7),
                        fontSize = 13.sp
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFF333333),
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SoundSelectCard(
    label: String,
    soundName: String,
    cardColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable {
                onClick()
            },
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(28.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(
                        width = 1.dp,
                        color = Color(0xFFC4C4C4),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = soundName,
                    color = Color(0xFF333333),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = ">",
                color = Color(0xFF9BA5B2),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun DaySelectButton(
    day: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) {
        Color(0xFF4974FF)
    } else {
        Color.White
    }

    val textColor = if (selected) {
        Color.White
    } else {
        Color(0xFF333333)
    }

    Box(
        modifier = Modifier
            .width(34.dp)
            .height(27.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = Color(0xFFD1D1D1),
                shape = RoundedCornerShape(7.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day,
            color = textColor,
            fontSize = 11.sp
        )
    }
}

@Composable
fun BottomActionButton(
    text: String,
    textColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(128.dp)
            .height(36.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F8FC))
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
@Composable
fun RecordScreen(
    logs: List<AlarmDismissLog>,
    onBackClick: () -> Unit
) {
    BackHandler {
        onBackClick()
    }

    val backgroundColor = Color(0xFF5588D0)
    val cardColor = Color(0xFFF1F6F7)
    val grayText = Color(0xFF9BA5B2)
    val darkText = Color(0xFF333333)
    val blueText = Color(0xFF4974FF)

    val chartLogs = logs
        .sortedBy { getDismissLogDateTime(it) }
        .takeLast(7)

    val logList = logs
        .sortedByDescending { getDismissLogDateTime(it) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(38.dp))

            Text(
                text = "Record",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 6.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Week Summary",
                        color = grayText,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    DismissTimeChart(
                        logs = chartLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(122.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Alarm Log",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (logList.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 5.dp
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "알람 해제 기록이 없습니다.",
                            color = grayText,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                logList.forEach { log ->
                    AlarmLogCard(
                        log = log,
                        cardColor = cardColor,
                        blueText = blueText,
                        darkText = darkText,
                        grayText = grayText
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun DismissTimeChart(
    logs: List<AlarmDismissLog>,
    modifier: Modifier = Modifier
) {
    val minMinutes = 6 * 60f
    val maxMinutes = 10 * 60f
    val rangeMinutes = maxMinutes - minMinutes

    if (logs.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "알람 해제 기록 없음",
                color = Color(0xFF9BA5B2),
                fontSize = 11.sp
            )
        }

        return
    }

    Column(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                listOf("10시", "9시", "8시", "7시", "6시").forEach { label ->
                    Text(
                        text = label,
                        color = Color(0xFF9BA5B2),
                        fontSize = 8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 2.dp)
            ) {
                val gridColor = Color(0xFFD8DEE4)
                val axisColor = Color(0xFFB8C0CC)
                val lineColor = Color(0xFF4974FF)

                for (i in 0..4) {
                    val y = size.height * (i / 4f)

                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.2f
                    )
                }

                drawLine(
                    color = axisColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.5f
                )

                drawLine(
                    color = axisColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.5f
                )

                val points = logs.mapIndexed { index, log ->
                    val minuteValue = (log.hour * 60 + log.minute)
                        .toFloat()
                        .coerceIn(minMinutes, maxMinutes)

                    val x = if (logs.size == 1) {
                        size.width / 2f
                    } else {
                        size.width / (logs.size - 1) * index
                    }

                    val yRatio = (minuteValue - minMinutes) / rangeMinutes
                    val y = size.height - yRatio * size.height

                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)

                        points.drop(1).forEach { point ->
                            lineTo(point.x, point.y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(
                            width = 3f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                points.forEach { point ->
                    drawCircle(
                        color = lineColor,
                        radius = 5f,
                        center = point
                    )

                    drawCircle(
                        color = Color.White,
                        radius = 2.5f,
                        center = point
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.width(32.dp))

            Row(
                modifier = Modifier.weight(1f)
            ) {
                logs.forEach { log ->
                    Text(
                        text = formatChartDate(log.date),
                        color = Color(0xFF9BA5B2),
                        fontSize = 7.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmLogCard(
    log: AlarmDismissLog,
    cardColor: Color,
    blueText: Color,
    darkText: Color,
    grayText: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = log.date,
                    color = grayText,
                    fontSize = 10.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = log.alarmName,
                    color = darkText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = log.timeText,
                    color = blueText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (log.amountMl > 0) "${log.amountMl}ml 섭취" else "해제 완료",
                    color = grayText,
                    fontSize = 9.sp
                )
            }
        }
    }
}

fun playAlarmSound(context: Context): MediaPlayer? {
    val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ?: return null

    return try {
        MediaPlayer().apply {
            setDataSource(context, ringtoneUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = true
            setOnPreparedListener { player ->
                player.start()
            }
            prepareAsync()
        }
    } catch (e: Exception) {
        println("알람음 재생 실패: ${e.message}")
        null
    }
}

suspend fun fetchDrinkRecordsFromServer(): List<AlarmDismissLog> {
    return withContext(Dispatchers.IO) {
        val body = httpGet("$SERVER_URL/records")
        val jsonArray = JSONArray(body)
        val logs = mutableListOf<AlarmDismissLog>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)

            val completedAt = item.optString("completed_at", "")
            val completedDateTime = parseServerDateTime(completedAt) ?: continue

            val result = item.optString("result", "")
            if (result != "SUCCESS") {
                continue
            }

            val amountMl = item.optString("amount_ml", "0").toIntOrNull() ?: 0
            val scheduledTime = item.optString("scheduled_time", "물 섭취 알람")

            logs.add(
                AlarmDismissLog(
                    id = i + 1,
                    alarmName = if (scheduledTime.isBlank()) "물 섭취 알람" else scheduledTime,
                    date = formatLocalDate(completedDateTime.toLocalDate()),
                    hour = completedDateTime.hour,
                    minute = completedDateTime.minute,
                    amountMl = amountMl
                )
            )
        }

        logs
    }
}

suspend fun syncServerTimeWithPhone() {
    withContext(Dispatchers.IO) {
        val phoneTimeMs = System.currentTimeMillis()

        val requestBody = JSONObject()
            .put("client_time_ms", phoneTimeMs)
            .toString()

        httpPostJson("$SERVER_URL/time/sync", requestBody)
    }
}

suspend fun requestAlarmStart(scheduledTime: String): Boolean {
    return withContext(Dispatchers.IO) {
        val phoneTimeMs = System.currentTimeMillis()

        val requestBody = JSONObject()
            .put("scheduled_time", scheduledTime)
            .put("client_time_ms", phoneTimeMs)
            .toString()

        val body = httpPostJson("$SERVER_URL/alarm/start", requestBody)
        val jsonObject = JSONObject(body)
        val status = jsonObject.optString("status", "")

        status == "started" || status == "already_running"
    }
}

suspend fun fetchAlarmStatusFromServer(): AlarmServerStatus {
    return withContext(Dispatchers.IO) {
        val body = httpGet("$SERVER_URL/alarm/status")
        val jsonObject = JSONObject(body)

        AlarmServerStatus(
            result = jsonObject.optString("result", "UNKNOWN"),
            amountMl = jsonObject.optInt("amount_ml", 0)
        )
    }
}

fun httpGet(urlText: String): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection

    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw Exception("HTTP $responseCode: $body")
        }

        body
    } finally {
        connection.disconnect()
    }
}

fun httpPostJson(urlText: String, jsonBody: String): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection

    return try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        connection.outputStream.use { output ->
            output.write(jsonBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw Exception("HTTP $responseCode: $body")
        }

        body
    } finally {
        connection.disconnect()
    }
}

fun parseServerDateTime(dateTimeText: String): LocalDateTime? {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    return try {
        LocalDateTime.parse(dateTimeText, formatter)
    } catch (e: Exception) {
        null
    }
}

fun isAlarmDueNow(
    alarm: AlarmData,
    now: LocalDateTime
): Boolean {
    val alarmTimeMatches = alarm.hour == now.hour && alarm.minute == now.minute

    if (!alarmTimeMatches) {
        return false
    }

    val baseDate = parseAlarmDate(alarm.date) ?: now.toLocalDate()

    if (alarm.days.isEmpty()) {
        return baseDate == now.toLocalDate()
    }

    if (now.toLocalDate().isBefore(baseDate)) {
        return false
    }

    val todayKorean = when (now.dayOfWeek) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    return alarm.days.contains(todayKorean)
}


fun findNextAlarm(
    alarms: List<AlarmData>,
    now: LocalDateTime
): NextAlarmInfo? {
    return alarms
        .mapNotNull { alarm ->
            val nextTime = getNextAlarmDateTime(
                alarm = alarm,
                now = now
            )

            if (nextTime == null) {
                null
            } else {
                NextAlarmInfo(
                    alarm = alarm,
                    dateTime = nextTime
                )
            }
        }
        .minByOrNull { it.dateTime }
}

fun getNextAlarmDateTime(
    alarm: AlarmData,
    now: LocalDateTime
): LocalDateTime? {
    val baseDate = parseAlarmDate(alarm.date) ?: now.toLocalDate()
    val alarmTime = LocalTime.of(alarm.hour, alarm.minute)

    if (alarm.days.isEmpty()) {
        val candidate = LocalDateTime.of(baseDate, alarmTime)

        return if (candidate.isAfter(now)) {
            candidate
        } else {
            null
        }
    }

    val targetDays = alarm.days
        .mapNotNull { koreanDayToDayOfWeek(it) }
        .toSet()

    if (targetDays.isEmpty()) {
        return null
    }

    val startDate = maxOf(now.toLocalDate(), baseDate)

    for (i in 0..13) {
        val date = startDate.plusDays(i.toLong())
        val candidate = LocalDateTime.of(date, alarmTime)

        if (
            !date.isBefore(baseDate) &&
            candidate.isAfter(now) &&
            targetDays.contains(date.dayOfWeek)
        ) {
            return candidate
        }
    }

    return null
}

fun formatRemainingTime(
    now: LocalDateTime,
    target: LocalDateTime
): String {
    val totalMinutes = ChronoUnit.MINUTES.between(now, target)

    if (totalMinutes <= 0) {
        return "곧 울림"
    }

    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60

    return when {
        days > 0 && hours > 0 -> "${days}일 ${hours}시간"
        days > 0 -> "${days}일"
        hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
        hours > 0 -> "${hours}시간"
        else -> "${minutes}분"
    }
}

fun koreanDayToDayOfWeek(day: String): DayOfWeek? {
    return when (day) {
        "월" -> DayOfWeek.MONDAY
        "화" -> DayOfWeek.TUESDAY
        "수" -> DayOfWeek.WEDNESDAY
        "목" -> DayOfWeek.THURSDAY
        "금" -> DayOfWeek.FRIDAY
        "토" -> DayOfWeek.SATURDAY
        "일" -> DayOfWeek.SUNDAY
        else -> null
    }
}

fun formatAlarmTime(
    hour: Int,
    minute: Int
): String {
    val amPm = if (hour < 12) {
        "AM"
    } else {
        "PM"
    }

    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }

    return String.format(
        Locale.US,
        "%02d:%02d %s",
        hour12,
        minute,
        amPm
    )
}

fun formatAlarmDate(
    millis: Long
): String {
    val date = Instant
        .ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    return formatLocalDate(date)
}

fun formatLocalDate(
    date: LocalDate
): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-M-dd")
    return date.format(formatter)
}

fun parseAlarmDate(
    dateText: String
): LocalDate? {
    return try {
        val formatter = DateTimeFormatter.ofPattern("yyyy-M-dd")
        LocalDate.parse(dateText, formatter)
    } catch (e: Exception) {
        null
    }
}

fun dateTextToMillis(
    dateText: String
): Long {
    val date = parseAlarmDate(dateText) ?: LocalDate.now()

    return date
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

fun getDismissLogDateTime(
    log: AlarmDismissLog
): LocalDateTime {
    val date = parseAlarmDate(log.date) ?: LocalDate.now()
    val time = LocalTime.of(log.hour, log.minute)

    return LocalDateTime.of(date, time)
}

fun formatChartDate(
    dateText: String
): String {
    val date = parseAlarmDate(dateText) ?: return dateText

    return "${date.monthValue}/${String.format(Locale.US, "%02d", date.dayOfMonth)}"
}

@Composable
fun CupIcon(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val outlineColor = Color(0xFF222222)
        val waterColor = Color(0xFF9FD5F4)
        val bubbleColor = Color.White

        val waterPath = Path().apply {
            moveTo(w * 0.31f, h * 0.50f)
            quadraticBezierTo(w * 0.50f, h * 0.43f, w * 0.69f, h * 0.50f)
            lineTo(w * 0.61f, h * 0.78f)
            quadraticBezierTo(w * 0.50f, h * 0.84f, w * 0.39f, h * 0.78f)
            close()
        }

        drawPath(
            path = waterPath,
            color = waterColor
        )

        val wavePath = Path().apply {
            moveTo(w * 0.31f, h * 0.50f)
            quadraticBezierTo(w * 0.50f, h * 0.43f, w * 0.69f, h * 0.50f)
        }

        drawPath(
            path = wavePath,
            color = Color.White,
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.025f,
            center = Offset(w * 0.42f, h * 0.62f)
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.018f,
            center = Offset(w * 0.55f, h * 0.58f)
        )

        drawCircle(
            color = bubbleColor,
            radius = w * 0.015f,
            center = Offset(w * 0.50f, h * 0.70f)
        )

        val cupSidePath = Path().apply {
            moveTo(w * 0.24f, h * 0.18f)
            lineTo(w * 0.36f, h * 0.86f)
            quadraticBezierTo(w * 0.50f, h * 0.94f, w * 0.64f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.18f)
        }

        drawPath(
            path = cupSidePath,
            color = outlineColor,
            style = Stroke(
                width = 3.2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawOval(
            color = outlineColor,
            topLeft = Offset(w * 0.24f, h * 0.11f),
            size = Size(w * 0.52f, h * 0.14f),
            style = Stroke(
                width = 3.2f,
                cap = StrokeCap.Round
            )
        )
    }
}
