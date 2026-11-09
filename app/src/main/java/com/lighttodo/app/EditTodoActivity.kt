package com.lighttodo.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lighttodo.app.data.TodoRepository
import com.lighttodo.app.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 单条待办的编辑页：边打字边自动保存并刷新小组件，不需要「保存」按钮。
 */
class EditTodoActivity : ComponentActivity() {

    private var listId: Int = 0
    private var todoId: Long = 0L
    private lateinit var repo: TodoRepository
    private lateinit var edit: EditText
    private var saveJob: Job? = null
    private var lastApplied: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_todo)

        listId = intent.getIntExtra(EXTRA_LIST_ID, 0)
        todoId = intent.getLongExtra(EXTRA_TODO_ID, 0L)
        repo = TodoRepository.get(this)

        val item = repo.snapshot(listId).firstOrNull { it.id == todoId }
        if (listId <= 0 || item == null) {
            finish()
            return
        }
        lastApplied = item.title

        edit = findViewById(R.id.edit)
        edit.setText(item.title)
        edit.setSelection(edit.text.length)
        edit.requestFocus()

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleSave(s?.toString().orEmpty())
            }
        })

        findViewById<TextView>(R.id.delete).setOnClickListener {
            lifecycleScope.launch {
                repo.delete(listId, todoId)
                WidgetUpdater.refreshNow(this@EditTodoActivity, listId)
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时把最后一次输入落库（防抖还没触发的情况下）
        saveJob?.cancel()
        val value = edit.text.toString().trim()
        if (value.isNotEmpty() && value != lastApplied) {
            lastApplied = value
            kotlinx.coroutines.runBlocking { repo.rename(listId, todoId, value) }
            WidgetUpdater.update(this, listId)
        }
    }

    private fun scheduleSave(text: String) {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(400)
            flush(text)
        }
    }

    private suspend fun flush(text: String) {
        val value = text.trim()
        if (value.isEmpty() || value == lastApplied) {
            return
        }
        lastApplied = value
        repo.rename(listId, todoId, value)
        WidgetUpdater.refreshNow(this, listId)
    }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
        const val EXTRA_TODO_ID = "todo_id"
    }
}
