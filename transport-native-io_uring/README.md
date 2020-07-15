## Google Summer of Code Project io_uring project

https://summerofcode.withgoogle.com/organizations/4616776010170368/#5479234484568064

The goal of this project is to add io_uring based transport for Linux to make Netty more efficient in terms of throughput and latency by reducing the number of syscalls.

### 
What is io_uring?

The new io_uring interface added to the Linux Kernel 5.1 is a high I/O performance scalable interface for fully asynchronous Linux syscalls. 

Here is my draft: https://github.com/netty/netty/pull/10356

### Potential Security Vulnerability

Io_uring consists of two ring buffers a submission queue and completion queue which are shared with the application(mmaped). I’m using JNI which allows to use C/C++ therefore  different security issues like buffer overflow no bounds checking etc. 
Another big problem because JNI calls are slow we use unsafe to access to ring buffer information(shared memory with kernel)  which is much faster for example(https://github.com/1Jo1/netty/blob/io-uring/transport-native-io_uring/src/main/java/io/netty/channel/uring/IOUringSubmissionQueue.java#L118)
Here I set C struct properties to the submission queue via unsafe
```
PlatformDependent.putByte(sqe + SQE_OP_CODE_FIELD, (byte) type.getOp());
PlatformDependent.putByte(sqe + SQE_FLAGS_FIELD, (byte) 0);
PlatformDependent.putShort(sqe + SQE_IOPRIO_FIELD, (short) 0);
PlatformDependent.putInt(sqe + SQE_FD_FIELD, fd);
PlatformDependent.putLong(sqe + SQE_OFFSET_FIELD, offset);
PlatformDependent.putLong(sqe + SQE_ADDRESS_FIELD, bufferAddress);
PlatformDependent.putInt(sqe + SQE_LEN_FIELD, length);
PlatformDependent.putInt(sqe + SQE_RW_FLAGS_FIELD, 0);
PlatformDependent.putLong(sqe + SQE_USER_DATA_FIELD, eventId);
```
sqe is a C struct pointer(https://github.com/axboe/liburing/blob/master/src/include/liburing/io_uring.h#L21) and these offsets SQE_OP_CODE_FIELD which points to a specifc property,
let's say I have other cpu architecture or an 32 Bit system and C compiler doesn't not gurantee that int size is 4 bytes it could be also 2 bytes, my offset calculation is only 64 bit system right now that would means that third parties may access other client data or manipulate data or execute code.
or my offset caluclation is wrong that would cause security vulnerability as well. Another security vulnerability would be JNI part which just to create these two Ring Buffers which are mmaped with the kernel

what I need to do to prevent the security vulnerability thoroughly many tests like test each unsafe execution, or  test each sqe property, 
probably, we won't support 32 Bit so I have to make sure that you can't execute it. 

