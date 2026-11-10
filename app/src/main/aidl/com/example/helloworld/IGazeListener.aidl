package com.example.helloworld;
oneway interface IGazeListener {
    void onTouch(float x, float y, boolean touching);
    void onStatus(int status);
}
