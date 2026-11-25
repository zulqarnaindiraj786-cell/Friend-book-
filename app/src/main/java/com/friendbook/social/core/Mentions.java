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
import com.friendbook.social.d1.documents.DocumentSnapshot;
import java.util.*;

/** Selected identities, not guessed names: persist UID + exact UTF-16 range and label. */
public final class Mentions {
    private static final int BLUE=0xFF1A73E8;
    private static final long SEARCH_CACHE_MS=5*60*1000L;
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
                public void onClick(View view) { if(uid.startsWith("bulk:")) { Ui.toast(view.getContext(),"Some "+(uid.endsWith("followers")?"followers":"friends")+" might receive notifications"); return; } Intent i=new Intent(view.getContext(),ProfileActivity.class); i.putExtra("uid",uid); view.getContext().startActivity(i); }
                public void updateDrawState(TextPaint paint) { paint.setColor(BLUE); paint.setUnderlineText(false); paint.setTypeface(Typeface.DEFAULT_BOLD); }
            },start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
    public static boolean hasBulk(List<Map<String,Object>> mentions) {
        if(mentions==null) return false;
        for(Map<String,Object> m:mentions) if("bulk:followers".equals(m.get("uid")) || "bulk:highlight".equals(m.get("uid"))) return true;
        return false;
    }
    public static void attach(EditText input) { attach(input, () -> null); }
    public static void attach(EditText input, java.util.function.Supplier<String> creator) {
        Handler handler=new Handler(Looper.getMainLooper());
        ListPopupWindow popup=new ListPopupWindow(input.getContext()); popup.setAnchorView(input); popup.setModal(false);
        popup.setWidth(input.getResources().getDisplayMetrics().widthPixels-Ui.dp(input.getContext(),24)); popup.setHeight(Ui.dp(input.getContext(),340));
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
                final String actor=Fb.uid();
                pending=() -> {
                    if(actor==null || !actor.equals(Fb.uid()) || !input.isAttachedToWindow()
                            || !input.hasWindowFocus() || !input.hasFocus()) return;
                    Map<String,DocumentSnapshot> found=new LinkedHashMap<>(); int[] done={0};
                    String cap=query.isEmpty()?query:Character.toUpperCase(query.charAt(0))+query.substring(1);
                    Set<String> terms=new LinkedHashSet<>(Arrays.asList(query,cap,query.toLowerCase(Locale.ROOT),query.toUpperCase(Locale.ROOT)));
                    for(String term:terms) {
                        Fb.db().collection("users").orderBy("displayName").startAt(term).endAt(term+"\uf8ff").limit(10).getCachedOrFetch(false,SEARCH_CACHE_MS).addOnCompleteListener(task -> {
                            if(request!=generation || !actor.equals(Fb.uid()) || !input.isAttachedToWindow()
                                    || !input.hasWindowFocus() || !input.hasFocus()) return;
                            if(task.isSuccessful() && task.getResult()!=null) for(DocumentSnapshot d:task.getResult()) found.put(d.getId(),d);
                            if(++done[0]<terms.size()) return;
                            List<DocumentSnapshot> users=new ArrayList<>(found.values()); List<String> names=new ArrayList<>();
                            for(DocumentSnapshot d:users) names.add(d.getString("displayName")==null?"User":d.getString("displayName"));
                            List<String> bulk=new ArrayList<>();
                            for(String tag:Arrays.asList("followers","highlight")) if(tag.startsWith(query.toLowerCase(Locale.ROOT))) bulk.add(tag);
                            List<String> rows=new ArrayList<>(); for(String tag:bulk) rows.add("@"+tag); rows.addAll(names);
                            if(rows.isEmpty()) return;
                            popup.setAdapter(new ArrayAdapter<String>(input.getContext(),android.R.layout.simple_list_item_1,rows) {
                                @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                                    LinearLayout row=Ui.row(input.getContext()); Ui.pad(row,12,10,12,10);
                                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                                    boolean isBulk=position<bulk.size(); int userIndex=position-bulk.size();
                                    ImageView avatar=new ImageView(input.getContext());
                                    row.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(input.getContext(),44),Ui.dp(input.getContext(),44)));
                                    if(isBulk) avatar.setImageResource(android.R.drawable.ic_menu_myplaces);
                                    else Ui.avatar(input.getContext(),avatar,users.get(userIndex).getString("photoUrl"));
                                    LinearLayout details=Ui.col(input.getContext()); Ui.pad(details,12,0,0,0);
                                    boolean owner=!isBulk && users.get(userIndex).getId().equals(creator.get());
                                    String title=isBulk?"@"+bulk.get(position):names.get(userIndex)+(owner?"  ◆ Creator":"");
                                    TextView label=Ui.tvBold(input.getContext(),title,16,owner?BLUE:Ui.txt(input.getContext()));
                                    LinearLayout titleRow=Ui.row(input.getContext()); titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                                    titleRow.addView(label);
                                    if(!isBulk && Boolean.TRUE.equals(users.get(userIndex).get("isVerified"))) {
                                        ImageView tick=new ImageView(input.getContext()); tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
                                        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(Ui.dp(input.getContext(),15),Ui.dp(input.getContext(),15)); size.leftMargin=Ui.dp(input.getContext(),4); titleRow.addView(tick,size);
                                    }
                                    details.addView(titleRow);
                                    String subtitle=isBulk?"Some "+(bulk.get(position).equals("followers")?"followers":"friends")+" might receive notifications":"Profile";
                                    details.addView(Ui.tv(input.getContext(),subtitle,12,Ui.sub(input.getContext())));
                                    row.addView(details); return row;
                                }
                            });
                            popup.setOnItemClickListener((parent,view,position,id) -> {
                                if(request!=generation || !actor.equals(Fb.uid())) return;
                                boolean isBulk=position<bulk.size(); int userIndex=position-bulk.size();
                                String label=isBulk?"@"+bulk.get(position):"@"+names.get(userIndex); Editable value=input.getText();
                                String selectedUid=isBulk?"bulk:"+bulk.get(position):users.get(userIndex).getId();
                                SpannableString selected=new SpannableString(label);
                                selected.setSpan(new Identity(selectedUid,label),0,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                value.replace(at,caret,selected); value.insert(at+label.length()," ");
                                input.setSelection(at+label.length()+1); popup.dismiss();
                                if(isBulk) Ui.toast(input.getContext(),"Public posts only · up to 20 eligible people per 24 hours · @followers requires the post owner");
                            });
                            popup.show(); input.requestFocus();
                        });
                    }
                };
                handler.postDelayed(pending,com.friendbook.social.core.SearchReadPolicy.DEBOUNCE_MS);
            }
        });
        input.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { handler.removeCallbacksAndMessages(null); popup.dismiss(); }
        });
    }
}
