
package com.dan.videostab

import android.app.Activity
import android.content.Context
import android.os.Environment
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        const val VIEW_MODE_ORIGINAL = 0
        const val VIEW_MODE_STABILIZED = 1
        const val VIEW_MODE_SPLIT_HORIZONTAL = 2
        const val VIEW_MODE_SPLIT_VERTICAL = 3

        const val ALGORITHM_GENERIC = 0
        const val ALGORITHM_GENERIC_B = 1
        const val ALGORITHM_STILL = 2
        const val ALGORITHM_HORIZONTAL_PANNING = 3
        const val ALGORITHM_HORIZONTAL_PANNING_B = 4
        const val ALGORITHM_VERTICAL_PANNING = 5
        const val ALGORITHM_VERTICAL_PANNING_B = 6
        const val ALGORITHM_PANNING = 7
        const val ALGORITHM_PANNING_B = 8
        const val ALGORITHM_NO_ROTATION = 9

        const val SAVE_FOLDER_SUFFIX = "/VideoStab"
        val SAVE_FOLDER = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath + SAVE_FOLDER_SUFFIX
    }

    var viewMode: Int = VIEW_MODE_SPLIT_HORIZONTAL
    var algorithm: Int = ALGORITHM_GENERIC
    var strength: Int = 1
    var encodeH265 = true
    var keepAudio = false

    init {
        loadProperties()
    }


    private fun forEachSettingProperty( listener: (KMutableProperty<*>)->Unit ) {
        for( member in this::class.declaredMemberProperties ) {
            if (member.visibility == KVisibility.PUBLIC && member is KMutableProperty<*>) {
                listener.invoke(member)
            }
        }
    }

    private fun loadProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> property.setter.call( this, preferences.getBoolean( property.name, property.getter.call(this) as Boolean ) )
                Int::class.createType() -> property.setter.call( this, preferences.getInt( property.name, property.getter.call(this) as Int ) )
                Long::class.createType() -> property.setter.call( this, preferences.getLong( property.name, property.getter.call(this) as Long ) )
                Float::class.createType() -> property.setter.call( this, preferences.getFloat( property.name, property.getter.call(this) as Float ) )
                String::class.createType() -> property.setter.call( this, preferences.getString( property.name, property.getter.call(this) as String ) )
            }
        }
    }

    fun saveProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> editor.putBoolean( property.name, property.getter.call(this) as Boolean )
                Int::class.createType() -> editor.putInt( property.name, property.getter.call(this) as Int )
                Long::class.createType() -> editor.putLong( property.name, property.getter.call(this) as Long )
                Float::class.createType() -> editor.putFloat( property.name, property.getter.call(this) as Float )
                String::class.createType() -> editor.putString( property.name, property.getter.call(this) as String )
            }
        }

        editor.apply()
    }
}
