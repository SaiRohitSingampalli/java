class Node<T> {
    T data;
    void setData(T data) { this.data = data; }
}
class IntNode extends Node<Integer> {
    @Override void setData(Integer data) { super.setData(data); }
}
