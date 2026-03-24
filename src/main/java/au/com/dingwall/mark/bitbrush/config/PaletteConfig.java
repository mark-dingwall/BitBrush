package au.com.dingwall.mark.bitbrush.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class PaletteConfig {

    @Bean
    public List<String> colorPalette() {
        // Office 2010 "Web" color picker palette (6x6x6 web color cube = 216 colors)
        // Reference: https://github.com/evoluteur/colorpicker
        return List.of(
            // Row 1: Pure black to pure blue
            "#000000", "#000033", "#000066", "#000099", "#0000CC", "#0000FF",
            // Row 2: Dark green to dark blue-green
            "#003300", "#003333", "#003366", "#003399", "#0033CC", "#0033FF",
            // Row 3: Green to cyan-blue
            "#006600", "#006633", "#006666", "#006699", "#0066CC", "#0066FF",
            // Row 4: Bright green to bright blue
            "#009900", "#009933", "#009966", "#009999", "#0099CC", "#0099FF",
            // Row 5: Lime to sky blue
            "#00CC00", "#00CC33", "#00CC66", "#00CC99", "#00CCCC", "#00CCFF",
            // Row 6: Pure green to pure cyan
            "#00FF00", "#00FF33", "#00FF66", "#00FF99", "#00FFCC", "#00FFFF",
            // Row 7: Dark red to dark purple
            "#330000", "#330033", "#330066", "#330099", "#3300CC", "#3300FF",
            // Row 8
            "#333300", "#333333", "#333366", "#333399", "#3333CC", "#3333FF",
            // Row 9
            "#336600", "#336633", "#336666", "#336699", "#3366CC", "#3366FF",
            // Row 10
            "#339900", "#339933", "#339966", "#339999", "#3399CC", "#3399FF",
            // Row 11
            "#33CC00", "#33CC33", "#33CC66", "#33CC99", "#33CCCC", "#33CCFF",
            // Row 12
            "#33FF00", "#33FF33", "#33FF66", "#33FF99", "#33FFCC", "#33FFFF",
            // Row 13: Medium red
            "#660000", "#660033", "#660066", "#660099", "#6600CC", "#6600FF",
            // Row 14
            "#663300", "#663333", "#663366", "#663399", "#6633CC", "#6633FF",
            // Row 15
            "#666600", "#666633", "#666666", "#666699", "#6666CC", "#6666FF",
            // Row 16
            "#669900", "#669933", "#669966", "#669999", "#6699CC", "#6699FF",
            // Row 17
            "#66CC00", "#66CC33", "#66CC66", "#66CC99", "#66CCCC", "#66CCFF",
            // Row 18
            "#66FF00", "#66FF33", "#66FF66", "#66FF99", "#66FFCC", "#66FFFF",
            // Row 19: Bright red
            "#990000", "#990033", "#990066", "#990099", "#9900CC", "#9900FF",
            // Row 20
            "#993300", "#993333", "#993366", "#993399", "#9933CC", "#9933FF",
            // Row 21
            "#996600", "#996633", "#996666", "#996699", "#9966CC", "#9966FF",
            // Row 22
            "#999900", "#999933", "#999966", "#999999", "#9999CC", "#9999FF",
            // Row 23
            "#99CC00", "#99CC33", "#99CC66", "#99CC99", "#99CCCC", "#99CCFF",
            // Row 24
            "#99FF00", "#99FF33", "#99FF66", "#99FF99", "#99FFCC", "#99FFFF",
            // Row 25: Red-orange
            "#CC0000", "#CC0033", "#CC0066", "#CC0099", "#CC00CC", "#CC00FF",
            // Row 26
            "#CC3300", "#CC3333", "#CC3366", "#CC3399", "#CC33CC", "#CC33FF",
            // Row 27
            "#CC6600", "#CC6633", "#CC6666", "#CC6699", "#CC66CC", "#CC66FF",
            // Row 28
            "#CC9900", "#CC9933", "#CC9966", "#CC9999", "#CC99CC", "#CC99FF",
            // Row 29
            "#CCCC00", "#CCCC33", "#CCCC66", "#CCCC99", "#CCCCCC", "#CCCCFF",
            // Row 30
            "#CCFF00", "#CCFF33", "#CCFF66", "#CCFF99", "#CCFFCC", "#CCFFFF",
            // Row 31: Pure red to magenta
            "#FF0000", "#FF0033", "#FF0066", "#FF0099", "#FF00CC", "#FF00FF",
            // Row 32
            "#FF3300", "#FF3333", "#FF3366", "#FF3399", "#FF33CC", "#FF33FF",
            // Row 33
            "#FF6600", "#FF6633", "#FF6666", "#FF6699", "#FF66CC", "#FF66FF",
            // Row 34
            "#FF9900", "#FF9933", "#FF9966", "#FF9999", "#FF99CC", "#FF99FF",
            // Row 35
            "#FFCC00", "#FFCC33", "#FFCC66", "#FFCC99", "#FFCCCC", "#FFCCFF",
            // Row 36: Yellow to white
            "#FFFF00", "#FFFF33", "#FFFF66", "#FFFF99", "#FFFFCC", "#FFFFFF"
        );
    }
}
