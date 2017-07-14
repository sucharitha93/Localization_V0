package amrita.cse.amuda.localization_v0;

/**
 * Created by SucharithaReddy on 6/6/2017.
 */

public interface QueueIntf {
    int size();
    boolean isEmpty();
    void enqueue(int e);
    int dequeue();
    int first();
}
