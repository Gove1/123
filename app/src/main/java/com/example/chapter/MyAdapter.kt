package com.example.chapter

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chapter.databinding.ItemRecyclerviewBinding
import java.io.File

/**
 * 13장 컴포넌트: RecyclerView Adapter
 * 일정 목록 아이템을 뷰홀더에 바인딩하고, 완료 체크 및 삭제 이벤트를 처리합니다.
 */
class MyViewHolder(val binding: ItemRecyclerviewBinding) : RecyclerView.ViewHolder(binding.root)

class MyAdapter(
    private val datas: MutableList<ScheduleItem>,
    private val onItemCheckChanged: (ScheduleItem, Boolean) -> Unit,
    private val onItemDelete: (ScheduleItem) -> Unit
) : RecyclerView.Adapter<MyViewHolder>() {

    override fun getItemCount(): Int = datas.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemRecyclerviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val item = datas[position]
        val binding = holder.binding

        // 1. 일정 제목 및 완료 취소선 설정
        binding.itemTitle.text = item.title
        if (item.isDone) {
            binding.itemTitle.paintFlags = binding.itemTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.itemTitle.alpha = 0.5f
        } else {
            binding.itemTitle.paintFlags = binding.itemTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.itemTitle.alpha = 1.0f
        }

        // 2. 날짜 및 시간
        binding.itemDatetime.text = "${item.date} ${item.time}"

        // 3. 알림 뱃지 표시
        binding.itemAlarmBadge.visibility = if (item.hasAlarm) View.VISIBLE else View.GONE

        // 4. 체크박스 상태 (이벤트 리스너 해제 후 재설정)
        binding.itemCheckbox.setOnCheckedChangeListener(null)
        binding.itemCheckbox.isChecked = item.isDone
        binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
            item.isDone = isChecked
            onItemCheckChanged(item, isChecked)
            notifyItemChanged(position)
        }

        // 5. 16장 사진 연계: 첨부된 사진이 있으면 표시, 없으면 13장의 기본 todo.jpg 표시
        if (!item.imagePath.isNullOrEmpty()) {
            val file = File(item.imagePath!!)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.itemImage.setImageBitmap(bitmap)
            } else {
                try {
                    val uri = Uri.parse(item.imagePath)
                    binding.itemImage.setImageURI(uri)
                } catch (e: Exception) {
                    binding.itemImage.setImageResource(R.drawable.todo)
                }
            }
        } else {
            binding.itemImage.setImageResource(R.drawable.todo)
        }

        // 6. 삭제 버튼 클릭 리스너
        binding.itemDeleteBtn.setOnClickListener {
            onItemDelete(item)
        }
    }
}
