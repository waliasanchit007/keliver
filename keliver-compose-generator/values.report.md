# Call records: what crosses (generated; do not edit)

Written by `:keliver-compose-generator:generateComposeApiValues` from `values.json`. The tests assert its counts do not shrink.

- 100 mirrored types, 314 producers (constructors, factories, constants), 46 receiver members
- 91 readable constructor-backed properties; 119 property error stubs (computed by Compose on the host)

## Does not cross

### a required parameter's type has no mirror (3)

- `androidx.compose.ui.graphics.Path#iterator(conicEvaluation:PathIterator.ConicEvaluation,tolerance:Float)`
- `androidx.compose.ui.text.AnnotatedString#flatMapAnnotations(transform:Function1<AnnotatedString.Range<AnnotatedString.Annotation>,List<AnnotatedString.Range<AnnotatedString.Annotation>>>)`
- `androidx.compose.ui.text.AnnotatedString#mapAnnotations(transform:Function1<AnnotatedString.Range<AnnotatedString.Annotation>,AnnotatedString.Range<AnnotatedString.Annotation>>)`

### a required parameter's type has no producer (32)

- `androidx.compose.foundation.shape.GenericShape#<init>(builder:Function3<Path,Size,LayoutDirection,Unit>)`
- `androidx.compose.ui.graphics.MeshGradientPainter#<init>(rows:Int,columns:Int,hasBicubicColor:Boolean,block:Function1<MeshGradientScope,Unit>)`
- `androidx.compose.ui.graphics.PathEffect.Companion#dashPathEffect(intervals:FloatArray,phase:Float)`
- `androidx.compose.ui.graphics.PixelMap#<init>(buffer:IntArray,width:Int,height:Int,bufferOffset:Int,stride:Int)`
- `androidx.compose.ui.graphics.computeCubicVerticalBounds(p0y:Float,p1y:Float,p2y:Float,p3y:Float,roots:FloatArray,index:Int)`
- `androidx.compose.ui.graphics.computeHorizontalBounds(segment:PathSegment,roots:FloatArray,index:Int)`
- `androidx.compose.ui.text.MultiParagraph#<init>(annotatedString:AnnotatedString,style:TextStyle,constraints:Constraints,density:Density,fontFamilyResolver:FontFamily.Resolver,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,overflow:TextOverflow)`
- `androidx.compose.ui.text.MultiParagraph#<init>(annotatedString:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,ellipsis:Boolean,width:Float,density:Density,resourceLoader:Font.ResourceLoader)`
- `androidx.compose.ui.text.MultiParagraph#<init>(annotatedString:AnnotatedString,style:TextStyle,width:Float,density:Density,fontFamilyResolver:FontFamily.Resolver,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,ellipsis:Boolean)`
- `androidx.compose.ui.text.MultiParagraph#<init>(intrinsics:MultiParagraphIntrinsics,constraints:Constraints,maxLines:Int,overflow:TextOverflow)`
- `androidx.compose.ui.text.MultiParagraph#<init>(intrinsics:MultiParagraphIntrinsics,maxLines:Int,ellipsis:Boolean,width:Float)`
- `androidx.compose.ui.text.MultiParagraphIntrinsics#<init>(annotatedString:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,density:Density,fontFamilyResolver:FontFamily.Resolver)`
- `androidx.compose.ui.text.MultiParagraphIntrinsics#<init>(annotatedString:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,density:Density,fontFamilyResolver:FontFamily.Resolver,softWrap:Boolean)`
- `androidx.compose.ui.text.MultiParagraphIntrinsics#<init>(annotatedString:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,density:Density,resourceLoader:Font.ResourceLoader)`
- `androidx.compose.ui.text.Paragraph(paragraphIntrinsics:ParagraphIntrinsics,constraints:Constraints,maxLines:Int,overflow:TextOverflow)`
- `androidx.compose.ui.text.Paragraph(paragraphIntrinsics:ParagraphIntrinsics,maxLines:Int,ellipsis:Boolean,width:Float)`
- `androidx.compose.ui.text.Paragraph(text:String,style:TextStyle,constraints:Constraints,density:Density,fontFamilyResolver:FontFamily.Resolver,spanStyles:List<AnnotatedString.Range<SpanStyle>>,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,overflow:TextOverflow)`
- `androidx.compose.ui.text.Paragraph(text:String,style:TextStyle,spanStyles:List<AnnotatedString.Range<SpanStyle>>,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,ellipsis:Boolean,width:Float,density:Density,resourceLoader:Font.ResourceLoader)`
- `androidx.compose.ui.text.Paragraph(text:String,style:TextStyle,width:Float,density:Density,fontFamilyResolver:FontFamily.Resolver,spanStyles:List<AnnotatedString.Range<SpanStyle>>,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,ellipsis:Boolean)`
- `androidx.compose.ui.text.ParagraphIntrinsics(text:String,style:TextStyle,annotations:List<AnnotatedString.Range<AnnotatedString.Annotation>>,density:Density,fontFamilyResolver:FontFamily.Resolver,placeholders:List<AnnotatedString.Range<Placeholder>>)`
- `androidx.compose.ui.text.ParagraphIntrinsics(text:String,style:TextStyle,annotations:List<AnnotatedString.Range<AnnotatedString.Annotation>>,density:Density,fontFamilyResolver:FontFamily.Resolver,placeholders:List<AnnotatedString.Range<Placeholder>>,softWrap:Boolean)`
- `androidx.compose.ui.text.ParagraphIntrinsics(text:String,style:TextStyle,spanStyles:List<AnnotatedString.Range<SpanStyle>>,placeholders:List<AnnotatedString.Range<Placeholder>>,density:Density,fontFamilyResolver:FontFamily.Resolver)`
- `androidx.compose.ui.text.ParagraphIntrinsics(text:String,style:TextStyle,spanStyles:List<AnnotatedString.Range<SpanStyle>>,placeholders:List<AnnotatedString.Range<Placeholder>>,density:Density,resourceLoader:Font.ResourceLoader)`
- `androidx.compose.ui.text.TextLayoutInput#<init>(text:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,softWrap:Boolean,overflow:TextOverflow,density:Density,layoutDirection:LayoutDirection,fontFamilyResolver:FontFamily.Resolver,constraints:Constraints)`
- `androidx.compose.ui.text.TextLayoutInput#<init>(text:AnnotatedString,style:TextStyle,placeholders:List<AnnotatedString.Range<Placeholder>>,maxLines:Int,softWrap:Boolean,overflow:TextOverflow,density:Density,layoutDirection:LayoutDirection,resourceLoader:Font.ResourceLoader,constraints:Constraints)`
- `androidx.compose.ui.text.TextLayoutResult#<init>(layoutInput:TextLayoutInput,multiParagraph:MultiParagraph,size:IntSize)`
- `androidx.compose.ui.text.TextMeasurer#<init>(defaultFontFamilyResolver:FontFamily.Resolver,defaultDensity:Density,defaultLayoutDirection:LayoutDirection,cacheSize:Int)`
- `androidx.compose.ui.text.buildAnnotatedString(builder:Function1<AnnotatedString.Builder,Unit>)`
- `androidx.compose.ui.text.font.FontFamily(typeface:Typeface)`
- `androidx.compose.ui.text.resolveDefaults(style:TextStyle,direction:LayoutDirection)`
- `androidx.compose.ui.text.style.LineHeightStyle#<init>(alignment:LineHeightStyle.Alignment,trim:LineHeightStyle.Trim)`
- `androidx.compose.ui.text.style.LineHeightStyle#<init>(alignment:LineHeightStyle.Alignment,trim:LineHeightStyle.Trim,mode:LineHeightStyle.Mode)`

### default expressions not found in the sources (5)

- `androidx.compose.ui.graphics.Brush.Companion#horizontalGradient(colorStops:Pair<Float,Color>...,startX:Float,endX:Float,tileMode:TileMode)`
- `androidx.compose.ui.graphics.Brush.Companion#linearGradient(colorStops:Pair<Float,Color>...,start:Offset,end:Offset,tileMode:TileMode)`
- `androidx.compose.ui.graphics.Brush.Companion#radialGradient(colorStops:Pair<Float,Color>...,center:Offset,radius:Float,tileMode:TileMode)`
- `androidx.compose.ui.graphics.Brush.Companion#sweepGradient(colorStops:Pair<Float,Color>...,center:Offset)`
- `androidx.compose.ui.graphics.Brush.Companion#verticalGradient(colorStops:Pair<Float,Color>...,startY:Float,endY:Float,tileMode:TileMode)`

### its guest signature equals another overload's (dropped parameters) (2)

- `androidx.compose.ui.text.AnnotatedString#<init>(text:String,spanStyles:List<AnnotatedString.Range<SpanStyle>>,paragraphStyles:List<AnnotatedString.Range<ParagraphStyle>>)`
- `androidx.compose.ui.text.font.Font(resId:Int,weight:FontWeight,style:FontStyle,loadingStrategy:FontLoadingStrategy,variationSettings:FontVariation.Settings)`

### the type is nested, hidden, excluded or a hand codec (47)

- `androidx.compose.ui.graphics.Color#<init>(value:ULong)`
- `androidx.compose.ui.graphics.Color(color:Int)`
- `androidx.compose.ui.graphics.Color(color:Long)`
- `androidx.compose.ui.graphics.Color(red:Float,green:Float,blue:Float,alpha:Float,colorSpace:ColorSpace)`
- `androidx.compose.ui.graphics.Color(red:Int,green:Int,blue:Int,alpha:Int)`
- `androidx.compose.ui.graphics.Color.Companion#Black`
- `androidx.compose.ui.graphics.Color.Companion#Blue`
- `androidx.compose.ui.graphics.Color.Companion#Cyan`
- `androidx.compose.ui.graphics.Color.Companion#DarkGray`
- `androidx.compose.ui.graphics.Color.Companion#Gray`
- `androidx.compose.ui.graphics.Color.Companion#Green`
- `androidx.compose.ui.graphics.Color.Companion#LightGray`
- `androidx.compose.ui.graphics.Color.Companion#Magenta`
- `androidx.compose.ui.graphics.Color.Companion#Red`
- `androidx.compose.ui.graphics.Color.Companion#Transparent`
- `androidx.compose.ui.graphics.Color.Companion#Unspecified`
- `androidx.compose.ui.graphics.Color.Companion#White`
- `androidx.compose.ui.graphics.Color.Companion#Yellow`
- `androidx.compose.ui.graphics.Color.Companion#hsl(hue:Float,saturation:Float,lightness:Float,alpha:Float,colorSpace:Rgb)`
- `androidx.compose.ui.graphics.Color.Companion#hsv(hue:Float,saturation:Float,value:Float,alpha:Float,colorSpace:Rgb)`
- `androidx.compose.ui.graphics.DefaultShadowColor`
- `androidx.compose.ui.graphics.Float16.Companion#Epsilon`
- `androidx.compose.ui.graphics.Float16.Companion#LowestValue`
- `androidx.compose.ui.graphics.Float16.Companion#MaxValue`
- `androidx.compose.ui.graphics.Float16.Companion#MinNormal`
- `androidx.compose.ui.graphics.Float16.Companion#MinValue`
- `androidx.compose.ui.graphics.Float16.Companion#NaN`
- `androidx.compose.ui.graphics.Float16.Companion#NegativeInfinity`
- `androidx.compose.ui.graphics.Float16.Companion#NegativeZero`
- `androidx.compose.ui.graphics.Float16.Companion#PositiveInfinity`
- `androidx.compose.ui.graphics.Float16.Companion#PositiveZero`
- `androidx.compose.ui.graphics.Interval#<init>(start:Float,end:Float,data:T?)`
- `androidx.compose.ui.graphics.IntervalTree#<init>()`
- `androidx.compose.ui.graphics.lerp(start:Color,stop:Color,fraction:Float)`
- `androidx.compose.ui.text.Bullet.Companion#DefaultIndentation`
- `androidx.compose.ui.text.Bullet.Companion#DefaultPadding`
- `androidx.compose.ui.text.Bullet.Companion#DefaultSize`
- `androidx.compose.ui.text.DeprecatedBridgeFontResourceLoader.Companion#from(fontFamilyResolver:FontFamily.Resolver)`
- `androidx.compose.ui.text.DeprecatedBridgeFontResourceLoader.Companion#lock`
- `androidx.compose.ui.text.StrongDirectionType.Companion#Ltr`
- `androidx.compose.ui.text.StrongDirectionType.Companion#None`
- `androidx.compose.ui.text.StrongDirectionType.Companion#Rtl`
- `androidx.compose.ui.text.font.FontListFontFamilyTypefaceAdapter.Companion#fontMatcher`
- `androidx.compose.ui.text.font.createFontFamilyResolver()`
- `androidx.compose.ui.text.font.createFontFamilyResolver(coroutineContext:CoroutineContext)`
- `androidx.compose.ui.text.style.TextForegroundStyle.Companion#from(brush:Brush?,alpha:Float)`
- `androidx.compose.ui.text.style.TextForegroundStyle.Companion#from(color:Color)`
