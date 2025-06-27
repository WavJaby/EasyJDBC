package com.wavjaby.jdbc.util;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;

public class LinkedList<E> extends AbstractSequentialList<E> implements List<E>, Serializable {
    transient int size = 0;

    transient Node<E> first;
    transient Node<E> last;

    public static class Node<E> {
        private E item;
        private Node<E> next;
        private Node<E> prev;

        Node(Node<E> prev, E element, Node<E> next) {
            this.item = element;
            this.next = next;
            this.prev = prev;
        }

        public E getItem() {
            return item;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public E get(int index) {
        checkElementIndex(index);
        return getNode(index).item;
    }

    public Node<E> getNode(int index) {
        Node<E> x;
        if (index < (size >> 1)) {
            x = first;
            for (int i = 0; i < index; i++)
                x = x.next;
        } else {
            x = last;
            for (int i = size - 1; i > index; i--)
                x = x.prev;
        }
        return x;
    }

    public Node<E> getFirst() {
        return first;
    }

    public Node<E> getLast() {
        return last;
    }

    @Override
    public boolean add(E e) {
        if (e == null)
            throw new NullValueException("Value can not be null");
        addAfter(e, last);
        return true;
    }

    /**
     * Move Node n before non-null Node succ.
     */
    public Node<E> moveBefore(Node<E> n, Node<E> succ) {
        remove(n);

        final Node<E> pred = succ.prev;
        succ.prev = n;
        if (pred == null)
            first = n;
        else
            pred.next = n;
        size++;
        modCount++;
        return n;
    }

    /**
     * Inserts element e before non-null Node succ.
     */
    public Node<E> addBefore(E e, Node<E> succ) {
        final Node<E> pred = succ.prev;
        final Node<E> newNode = new Node<>(pred, e, succ);
        succ.prev = newNode;
        if (pred == null)
            first = newNode;
        else
            pred.next = newNode;
        size++;
        modCount++;
        return newNode;
    }

    /**
     * Inserts element e after non-null Node succ.
     */
    public Node<E> addAfter(E e, Node<E> succ) {
        final Node<E> next = succ.next;
        final Node<E> newNode = new Node<>(succ, e, next);
        succ.next = newNode;
        if (next == null)
            last = newNode;
        else
            next.prev = newNode;
        size++;
        modCount++;
        return newNode;
    }

    @Override
    public E remove(int index) {
        checkElementIndex(index);
        return remove(getNode(index));
    }

    @Override
    public boolean remove(Object o) {
        if (o == null)
            throw new NullValueException("Value can not be null");
        for (Node<E> x = first; x != null; x = x.next) {
            if (o.equals(x.item)) {
                remove(x);
                return true;
            }
        }
        return false;
    }

    /**
     * Unlinks non-null node x.
     */
    public E remove(Node<E> x) {
        // assert x != null;
        final E element = x.item;
        final Node<E> next = x.next;
        final Node<E> prev = x.prev;

        if (prev == null) {
            first = next;
        } else {
            prev.next = next;
        }

        if (next == null) {
            last = prev;
        } else {
            next.prev = prev;
        }

        x.prev = null;
        x.next = null;
        x.item = null;
        size--;
        modCount++;
        return element;
    }

    @Override
    public int indexOf(Object o) {
        if (o == null)
            throw new NullValueException("Value can not be null");
        int index = 0;
        for (Node<E> x = first; x != null; x = x.next) {
            if (o.equals(x.item))
                return index;
            index++;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (o == null)
            throw new NullValueException("Value can not be null");
        int index = size;
        for (Node<E> x = last; x != null; x = x.prev) {
            index--;
            if (o.equals(x.item))
                return index;
        }
        return -1;
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }

    private String outOfBoundsMsg(int index) {
        return "Index: " + index + ", Size: " + size;
    }


    @Override
    @Nonnull
    public ListIterator<E> listIterator(int index) {
        checkPositionIndex(index);
        return new ListItr(index);
    }

    @Override
    @Nonnull
    public Iterator<E> iterator() {
        return new DescendingIterator();
    }

    private class DescendingIterator implements Iterator<E> {
        private final ListItr itr = new ListItr(size());

        public boolean hasNext() {
            return itr.hasPrevious();
        }

        public E next() {
            return itr.previous();
        }

        public void remove() {
            itr.remove();
        }
    }

    private class ListItr implements ListIterator<E> {
        private Node<E> lastReturned;
        private Node<E> next;
        private int nextIndex;
        private int expectedModCount = modCount;

        ListItr(int index) {
            // assert isPositionIndex(index);
            next = (index == size) ? null : getNode(index);
            nextIndex = index;
        }

        public boolean hasNext() {
            return nextIndex < size;
        }

        public E next() {
            checkForComodification();
            if (!hasNext())
                throw new NoSuchElementException();

            lastReturned = next;
            next = next.next;
            nextIndex++;
            return lastReturned.item;
        }

        public boolean hasPrevious() {
            return nextIndex > 0;
        }

        public E previous() {
            checkForComodification();
            if (!hasPrevious())
                throw new NoSuchElementException();

            lastReturned = next = (next == null) ? last : next.prev;
            nextIndex--;
            return lastReturned.item;
        }

        public int nextIndex() {
            return nextIndex;
        }

        public int previousIndex() {
            return nextIndex - 1;
        }

        public void remove() {
            checkForComodification();
            if (lastReturned == null)
                throw new IllegalStateException();

            Node<E> lastNext = lastReturned.next;
            LinkedList.this.remove(lastReturned);
            if (next == lastReturned)
                next = lastNext;
            else
                nextIndex--;
            lastReturned = null;
            expectedModCount++;
        }

        public void set(E e) {
            if (lastReturned == null)
                throw new IllegalStateException();
            checkForComodification();
            lastReturned.item = e;
        }

        public void add(E e) {
            checkForComodification();
            lastReturned = null;
            if (next == null)
                addAfter(e, last);
            else
                addBefore(e, next);
            nextIndex++;
            expectedModCount++;
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            while (modCount == expectedModCount && nextIndex < size) {
                action.accept(next.item);
                lastReturned = next;
                next = next.next;
                nextIndex++;
            }
            checkForComodification();
        }

        final void checkForComodification() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }
    }

    public static class NullValueException extends RuntimeException {
        public NullValueException(String message) {
            super(message);
        }
    }
}
