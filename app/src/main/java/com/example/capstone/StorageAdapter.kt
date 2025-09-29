package com.example.capstone

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.example.capstone.databinding.ItemStorageBinding


class StorageAdapter(
    private val videoList: MutableList<VideoItem>,
    private val onDeleteCallback: (VideoItem) -> Unit,
    private val onItemClick: (VideoItem) -> Unit  // 리스트가 클릭되었을 때 받아올 수 있도록
) : RecyclerView.Adapter<StorageAdapter.StorageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageViewHolder {
        val binding = ItemStorageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StorageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StorageViewHolder, position: Int) {
        val currentItem = videoList[position]

        holder.binding.dateTextView.text = currentItem.date
        holder.binding.timeTextView.text = currentItem.time
        holder.binding.locationTextView.text = currentItem.location
        holder.binding.videoTimeTextView.text = currentItem.videoTime
        holder.binding.videoSizeTextView.text = currentItem.videoSize


        // 각 아이템 뷰 전체에 클릭 리스너를 설정 -> 리사이클러뷰는 이렇게 따로 클릭 리스너 설정을 해줘야한다함.
        holder.itemView.setOnClickListener {
            onItemClick(currentItem)
        }


        // 쓰레기통 버튼 클릭했을 때 팝업창 띄우기!!!
        holder.binding.deleteButton.setOnClickListener {
            val layoutInflater = LayoutInflater.from(holder.itemView.context)
            val dialogView = layoutInflater.inflate(R.layout.dialog, null)

            val dialog = AlertDialog.Builder(holder.itemView.context)
                .setView(dialogView)
                .create()

            val btnNo = dialogView.findViewById<Button>(R.id.btnNo)
            val btnYes = dialogView.findViewById<Button>(R.id.btnYes)

            btnNo.setOnClickListener {
                dialog.dismiss()
            }

            btnYes.setOnClickListener {
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    // 삭제하기 전에 어떤 아이템인지 변수에 저장
                    val itemToRemove = videoList[currentPosition]

                    videoList.removeAt(currentPosition)
                    notifyItemRemoved(currentPosition)

                    // 콜백 함수를 호출할 때 삭제된 아이템을 넘겨줌
                    onDeleteCallback(itemToRemove)
                }
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    override fun getItemCount(): Int {
        return videoList.size
    }

    // 외부에서 데이터를 교체할 수 있는 함수
    fun updateList(newList: List<VideoItem>) {
        videoList.clear()
        videoList.addAll(newList)
        notifyDataSetChanged()
    }

    inner class StorageViewHolder(val binding: ItemStorageBinding) :
        RecyclerView.ViewHolder(binding.root)
}