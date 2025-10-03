package com.example.capstone

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.skt.tmap.TMapView

// fragment_map.xml 레이아웃을 사용하는 프래그먼트 클래스
class MapFragment : Fragment(R.layout.fragment_map) {

    // onViewCreated는 프래그먼트의 뷰가 성공적으로 생성되었을 때 호출됩니다.
    // UI와 관련된 모든 작업은 여기서 하는 것이 안전합니다.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. TMapView 객체 생성 및 API 키 설정
        val tMapView = TMapView(requireContext()).apply {
            setSKTMapApiKey("맵키 적는 위치")
            setCenterPoint(126.9784147, 37.5666805) // 초기 위치 (서울시청)
            setZoomLevel(15)
        }

        // 2. XML 레이아웃에 정의된 FrameLayout 컨테이너를 찾아서 TMapView를 추가
        val tmapContainer = view.findViewById<FrameLayout>(R.id.tmap_container)
        tmapContainer.addView(tMapView)
    }
}