package com.friendbook.social.core;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.*;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import java.util.*;

/** Selected identities, not guessed names: persist UID + exact UTF-16 range and label. */
public final class Mentions {
    private static final int BLUE=0xFF1A73E8;
    private Mentions() {}
    private static class Identity extends ForegroundColorSpan {
        final String uid,label;
        Identity(String uid,String label) { super(BLUE); this.uid=uid; this.label=label; }
    }
    private static final class ReplyIdentity extends Identity {
        ReplyIdentity(String uid,String label) { super(uid,label); }
    }
    /** Insert the actual reply author's identity, never the post owner or a name lookup. */
    public static void setReplyTarget(EditText input,String uid,String name) {
        String display=name==null || name.trim().isEmpty()?"User":name.trim();
        String label="@"+display;
        if(!MentionPolicy.valid(label,0,label.length(),label,uid)) return;
        clearReplyTarget(input);
        Editable text=input.getText();
        for(Identity span:text.getSpans(0,text.length(),Identity.class)) {
            int start=text.getSpanStart(span),end=text.getSpanEnd(span);
            if(uid.equals(span.uid) && MentionPolicy.valid(text,start,end,span.label,span.uid)) {
                input.setSelection(text.length()); return;
            }
        }
        SpannableString prefix=new SpannableString(label+" ");
        prefix.setSpan(new ReplyIdentity(uid,label),0,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.insert(0,prefix);
        input.setSelection(text.length());
    }
    /** Cancel/switch only removes an untouched auto-prefix; never erase a user's edited draft. */
    public static void clearReplyTarget(EditText input) {
        Editable text=input.getText();
        for(ReplyIdentity span:text.getSpans(0,text.length(),ReplyIdentity.class)) {
            int start=text.getSpanStart(span),end=text.getSpanEnd(span);
            boolean intact=MentionPolicy.valid(text,start,end,span.label,span.uid);
            text.removeSpan(span);
            if(intact) {
                if(end<text.length() && text.charAt(end)==' ') end++;
                text.delete(start,end);
            }
        }
    }
    public static List<Map<String,Object>> read(Object value) {
        List<Map<String,Object>> result=new ArrayList<>();
        if(value instanceof List) for(Object entry:(List<?>)value) {
            if(entry instanceof Map && result.size()<30) {
                Map<String,Object> m=new HashMap<>();
                for(Map.Entry<?,?> e:((Map<?,?>)entry).entrySet()) if(e.getKey() instanceof String) m.put((String)e.getKey(),e.getValue());
                result.add(m);
            }
        }
        return result;
    }
    public static void restore(EditText input,String content,List<Map<String,Object>> mentions) {
        input.setText(content==null?"":content);
        Editable value=input.getText();
        for(Map<String,Object> m:mentions) {
            Object a=m.get("start"),b=m.get("end"),label=m.get("label"),uid=m.get("uid");
            if(!(a instanceof Number) || !(b instanceof Number) || !(label instanceof String) || !(uid instanceof String)) continue;
            int start=((Number)a).intValue(),end=((Number)b).intValue();
            if(MentionPolicy.valid(value,start,end,(String)label,(String)uid))
                value.setSpan(new Identity((String)uid,(String)label),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
    public static List<Map<String,Object>> capture(EditText input) {
        Editable text=input.getText(); String raw=text.toString(); int trim=0;
        while(trim<raw.length() && raw.charAt(trim)<=32) trim++;
        List<Map<String,Object>> result=new ArrayList<>();
        for(Identity span:text.getSpans(0,text.length(),Identity.class)) {
            int start=text.getSpanStart(span),end=text.getSpanEnd(span);
            if(start>=trim && MentionPolicy.valid(raw,start,end,span.label,span.uid) && result.size()<30) {
                Map<String,Object> m=new HashMap<>(); m.put("uid",span.uid); m.put("label",span.label);
                m.put("start",start-trim); m.put("end",end-trim); result.add(m);
            }
        }
        return result;
    }
    public static CharSequence styled(CharSequence content,List<Map<String,Object>> mentions) {
        SpannableString text=new SpannableString(content==null?"":content);
        if(mentions==null) return text;
        for(Map<String,Object> m:mentions) {
            Object a=m.get("start"),b=m.get("end"),u=m.get("uid"),label=m.get("label");
            if(!(a instanceof Number) || !(b instanceof Number) || !(u instanceof String) || !(label instanceof String)) continue;
            int start=((Number)a).intValue(),end=((Number)b).intValue(); String uid=(String)u;
            if(!MentionPolicy.valid(text,start,end,(String)label,uid)) continue;
            text.setSpan(new ClickableSpan() {
                public void onClick(View view) { Intent i=new Intent(view.getContext(),ProfileActivity.class); i.putExtra("uid",uid); view.getContext().startActivity(i); }
                public void updateDrawState(TextPaint paint) { paint.setColor(BLUE); paint.setUnderlineText(false); paint.setTypeface(Typeface.DEFAULT_BOLD); }
            },start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
    public static void attach(EditText input) { attach(input, () -> null); }
    public static void attach(EditText input, java.util.function.Supplier<String> creator) {
        Handler handler=new Handler(Looper.getMainLooper());
        ListPopupWindow popup=new ListPopupWindow(input.getContext()); popup.setAnchorView(input); popup.setModal(false);
        popup.setWidth(Ui.dp(input.getContext(),280)); popup.setHeight(Ui.dp(input.getContext(),200));
        input.addTextChangedListener(new TextWatcher() {
            int generation=0;
            Runnable pending;
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {}
            public void afterTextChanged(Editable editable) {
                for(Identity span:editable.getSpans(0,editable.length(),Identity.class)) {
                    int start=editable.getSpanStart(span),end=editable.getSpanEnd(span);
                    if(start<0 || end<=start || !editable.subSequence(start,end).toString().equals(span.label)) editable.removeSpan(span);
                }
                int request=++generation; if(pending!=null) handler.removeCallbacks(pending); popup.dismiss();
                int caret=input.getSelectionStart(); if(caret<0) return;
                String prefix=editable.toString().substring(0,caret); int at=prefix.lastIndexOf('@');
                if(at<0 || (at>0 && !Character.isWhitespace(prefix.charAt(at-1)))) return;
                for(Identity span:editable.getSpans(at,caret,Identity.class)) if(editable.getSpanStart(span)==at) return;
                String query=prefix.substring(at+1); if(query.length()>40 || query.contains("\n") || query.contains("@")) return;
                pending=() -> {
                    Map<String,DocumentSnapshot> found=new LinkedHashMap<>(); int[] done={0};
                    String cap=query.isEmpty()?query:Character.toUpperCase(query.charAt(0))+query.substring(1);
                    Set<String> terms=new LinkedHashSet<>(Arrays.asList(query,cap,query.toLowerCase(Locale.ROOT),query.toUpperCase(Locale.ROOT)));
                    for(String term:terms) {
                        Fb.db().collection("users").orderBy("displayName").startAt(term).endAt(term+"\uf8ff").limit(10).get().addOnCompleteListener(task -> {
                            if(request!=generation || !input.isAttachedToWindow()) return;
                            if(task.isSuccessful() && task.getResult()!=null) for(DocumentSnapshot d:task.getResult()) found.put(d.getId(),d);
                            if(++done[0]<terms.size()) return;
                            List<DocumentSnapshot> users=new ArrayList<>(found.values()); List<String> names=new ArrayList<>();
                            for(DocumentSnapshot d:users) names.add(d.getString("displayName")==null?"User":d.getString("displayName"));
                            if(names.isEmpty()) return;
                            popup.setAdapter(new ArrayAdapter<String>(input.getContext(),android.R.layout.simple_list_item_1,names) {
                                @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                                    LinearLayout row=Ui.row(input.getContext()); Ui.pad(row,8,6,8,6);
                                    ImageView avatar=new ImageView(input.getContext());
                                    row.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(input.getContext(),32),Ui.dp(input.getContext(),32)));
                                    Ui.avatar(input.getContext(),avatar,users.get(position).getString("photoUrl"));
                                    boolean owner=users.get(position).getId().equals(creator.get());
                                    TextView label=Ui.tvBold(input.getContext(),"  "+names.get(position)+(owner?"  ◆ Creator":""),14,owner?BLUE:Ui.txt(input.getContext()));
                                    row.addView(label); return row;
                                }
                            });
                            popup.setOnItemClickListener((parent,view,position,id) -> {
                                if(request!=generation) return;
                                String label="@"+names.get(position); Editable value=input.getText();
                                SpannableString selected=new SpannableString(label);
                                selected.setSpan(new Identity(users.get(position).getId(),label),0,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                value.replace(at,caret,selected); value.insert(at+label.length()," ");
                                input.setSelection(at+label.length()+1); popup.dismiss();
                            });
                            popup.show(); input.requestFocus();
                        });
                    }
                };
                handler.postDelayed(pending,250);
            }
        });
        input.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { handler.removeCallbacksAndMessages(null); popup.dismiss(); }
        });
    }
}
