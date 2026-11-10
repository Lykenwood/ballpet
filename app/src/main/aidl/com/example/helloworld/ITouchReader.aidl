package com.example.helloworld;
import com.example.helloworld.IGazeListener;
interface ITouchReader {
    void start(IGazeListener listener) = 1;
    void stop() = 2;
    void destroy() = 16777114;
}
