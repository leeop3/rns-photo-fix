// Updated write method in BluetoothService.kt

// other existing code above

fun write(data: ByteArray) {
    // Proper condition check for data size between 83 and 95
    if (data.size in 83..95) {
        // Existing functionality
    } else {
        // handling for invalid size
    }
    
    // other existing code below
}