package com.google.firebase.database;

import androidx.annotation.NonNull;

public interface ValueEventListener {
    void onDataChange(@NonNull DataSnapshot snapshot);
    void onCancelled(@NonNull DatabaseError error);
}
