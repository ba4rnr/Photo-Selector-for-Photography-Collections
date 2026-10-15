package com.photoselector.core;

/**
 * 垃圾桶里的一条：记住"哪张照片"和"它原来排在第几位"。
 *
 * <p>后者是撤销（把照片放回原位）用的。注意这里存的只是引用，
 * 不是文件的复制品——这也是整个垃圾桶的设计核心。
 */
public record TrashedItem(TargetEntry entry, int originalIndex) {
}
