package org.luma.music

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.widget.*
import android.view.WindowInsets
import com.google.android.material.button.MaterialButton

class WhatsNewActivity:MaterialActivity() {
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16)}
        root.addView(LumaTextView(this).apply {text="Что нового";textSize=28f})
        val output=SpannableStringBuilder()
        assets.open("CHANGELOG.md").use {input->
            val bytes=ByteArray(128*1024);var count=0
            while(count<bytes.size){val n=input.read(bytes,count,bytes.size-count);if(n<0)break;count+=n}
            val text=String(bytes,0,count,Charsets.UTF_8)
            text.lineSequence().take(1500).forEach {line->
                val heading=line.startsWith("#")
                val clean=if(heading)line.trimStart('#',' ') else if(line.startsWith("- "))"• "+line.drop(2) else line
                val start=output.length;output.append(clean).append('\n')
                if(heading){
                    output.setSpan(StyleSpan(android.graphics.Typeface.BOLD),start,output.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(RelativeSizeSpan(1.18f),start,output.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        val scroll=ScrollView(this)
        scroll.addView(TextView(this).apply {text=output;textSize=15f;setTextIsSelectable(true);setPadding(0,12,0,12)})
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(MaterialButton(this).apply {text=L10n.text(this@WhatsNewActivity,"Назад");setOnClickListener{finish()}})
        root.setOnApplyWindowInsetsListener {v,i->val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(24+b.left,16+b.top,24+b.right,16+b.bottom);i}
        setContentView(root);root.requestApplyInsets()
    }
}
