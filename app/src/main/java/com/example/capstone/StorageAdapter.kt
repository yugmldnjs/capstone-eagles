package com.example.capstone

import android.app.AlertDialog
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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

        // 🔴🔴🔴 썸네일 로딩 로직 시작 🔴🔴🔴
        Glide.with(holder.itemView.context) // 1. Glide를 현재 아이템뷰의 context로 초기화
            .load(Uri.parse(currentItem.videoPath)) // 2. 비디오 경로(URI)를 로드
            .placeholder(R.drawable.copy) // 3. 로딩 중에 보여줄 기본 이미지
            .error(R.drawable.copy) // 4. 에러 발생 시 보여줄 기본 이미지
            .into(holder.binding.thumbnailImageView) // 5. 이미지를 표시할 ImageView 지정
        // 🔴🔴🔴 썸네일 로딩 로직 끝 🔴🔴🔴

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