using System;
using System.Drawing;
using System.Windows.Forms;

namespace VibertemisManager.App;

internal static class UiTheme
{
    public static readonly Color Accent = Color.FromArgb(0, 130, 140);
    public static readonly Color AccentText = Color.White;

    public static readonly Padding OuterPadding = new(16, 16, 16, 16);
    public static readonly Padding CardPadding = new(12, 10, 12, 10);
    public static readonly Padding SectionPadding = new(0, 0, 0, 8);
    public static readonly Padding RowPadding = new(0, 4, 0, 4);

    // One instance per role, shared by every control that asks for it.
    // Body text is 10.5 pt rather than the system default so the window
    // stays readable without the owner enlarging it, and Windows DPI
    // scaling has room to work from there.
    private static readonly FontFamily UiFontFamily = SystemFonts.MessageBoxFont?.FontFamily ?? FontFamily.GenericSansSerif;
    private static readonly Font Body = new(UiFontFamily, 10.5f);
    private static readonly Font Heading = new(UiFontFamily, 13f, FontStyle.Bold);
    private static readonly Font SectionHeading = new(UiFontFamily, 11f, FontStyle.Bold);
    private static readonly Font Code = new("Consolas", 22f, FontStyle.Bold);

    public static Font BodyFont() => Body;
    public static Font HeaderFont() => Heading;
    public static Font SectionFont() => SectionHeading;
    public static Font CodeFont() => Code;

    // Height of one body line measured at 96 DPI, so a caller can size a
    // control in logical units and let the form's DPI scaling apply it.
    public static int TextHeightLogical() => (int)Math.Ceiling(Body.GetHeight(96f));

    public enum ButtonRole { Primary, Secondary, Demoted }

    public static void ApplyButton(Button button, ButtonRole role)
    {
        button.AutoSize = true;
        button.AutoSizeMode = AutoSizeMode.GrowAndShrink;
        button.UseCompatibleTextRendering = false;
        button.Padding = new Padding(12, 6, 12, 6);
        button.Margin = new Padding(0, 4, 8, 4);

        // System flat style ignores BackColor, so the teal accent
        // would never paint. Use Standard so the platform text
        // colour and the accent background both render, and
        // override only on the primary button. High-contrast
        // Windows owns the colours from here.
        if (role == ButtonRole.Primary && !SystemInformation.HighContrast)
        {
            button.FlatStyle = FlatStyle.Standard;
            button.BackColor = Accent;
            button.ForeColor = AccentText;
            button.FlatAppearance.BorderColor = Accent;
            button.FlatAppearance.BorderSize = 1;
            button.FlatAppearance.MouseDownBackColor = Darken(Accent, 0.15f);
            button.FlatAppearance.MouseOverBackColor = Darken(Accent, 0.05f);
        }
        else
        {
            button.FlatStyle = FlatStyle.System;
        }
    }

    public static void ApplyForm(Form form)
    {
        // Controls inherit this, so body text and buttons are one size
        // everywhere unless a control deliberately overrides it.
        form.Font = Body;
        form.AutoScaleMode = AutoScaleMode.Dpi;
    }

    public static Color Darken(Color color, float amount)
    {
        amount = System.Math.Clamp(amount, 0f, 1f);
        return Color.FromArgb(
            color.A,
            (int)(color.R * (1f - amount)),
            (int)(color.G * (1f - amount)),
            (int)(color.B * (1f - amount)));
    }
}
