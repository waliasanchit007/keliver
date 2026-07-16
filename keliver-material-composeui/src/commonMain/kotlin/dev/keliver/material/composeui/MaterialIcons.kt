/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The curated name -> ImageVector map behind the Icon widget (the material
 * icons CORE set — ships with compose.material, no extra dependency). Names
 * match `Icons.Filled` member names. Add here + list in WIDGET_PARITY.md.
 */
internal val materialIconByName: Map<String, ImageVector> = mapOf(
  "AccountBox" to Icons.Filled.AccountBox,
  "AccountCircle" to Icons.Filled.AccountCircle,
  "Add" to Icons.Filled.Add,
  "AddCircle" to Icons.Filled.AddCircle,
  "ArrowBack" to Icons.Filled.ArrowBack,
  "ArrowDropDown" to Icons.Filled.ArrowDropDown,
  "ArrowForward" to Icons.Filled.ArrowForward,
  "Build" to Icons.Filled.Build,
  "Call" to Icons.Filled.Call,
  "Check" to Icons.Filled.Check,
  "CheckCircle" to Icons.Filled.CheckCircle,
  "Clear" to Icons.Filled.Clear,
  "Close" to Icons.Filled.Close,
  "Create" to Icons.Filled.Create,
  "DateRange" to Icons.Filled.DateRange,
  "Delete" to Icons.Filled.Delete,
  "Done" to Icons.Filled.Done,
  "Edit" to Icons.Filled.Edit,
  "Email" to Icons.Filled.Email,
  "ExitToApp" to Icons.Filled.ExitToApp,
  "Face" to Icons.Filled.Face,
  "Favorite" to Icons.Filled.Favorite,
  "FavoriteBorder" to Icons.Filled.FavoriteBorder,
  "Home" to Icons.Filled.Home,
  "Info" to Icons.Filled.Info,
  "KeyboardArrowDown" to Icons.Filled.KeyboardArrowDown,
  "KeyboardArrowLeft" to Icons.Filled.KeyboardArrowLeft,
  "KeyboardArrowRight" to Icons.Filled.KeyboardArrowRight,
  "KeyboardArrowUp" to Icons.Filled.KeyboardArrowUp,
  "List" to Icons.Filled.List,
  "LocationOn" to Icons.Filled.LocationOn,
  "Lock" to Icons.Filled.Lock,
  "MailOutline" to Icons.Filled.MailOutline,
  "Menu" to Icons.Filled.Menu,
  "MoreVert" to Icons.Filled.MoreVert,
  "Notifications" to Icons.Filled.Notifications,
  "Person" to Icons.Filled.Person,
  "Phone" to Icons.Filled.Phone,
  "Place" to Icons.Filled.Place,
  "PlayArrow" to Icons.Filled.PlayArrow,
  "Refresh" to Icons.Filled.Refresh,
  "Search" to Icons.Filled.Search,
  "Send" to Icons.Filled.Send,
  "Settings" to Icons.Filled.Settings,
  "Share" to Icons.Filled.Share,
  "ShoppingCart" to Icons.Filled.ShoppingCart,
  "Star" to Icons.Filled.Star,
  "ThumbUp" to Icons.Filled.ThumbUp,
  "Warning" to Icons.Filled.Warning,
  // ── P1-6: extended-set additions (material-icons-extended). Every name a real
  // fintech screen asked for and got the ⓘ fallback, plus common UI verbs.
  // The map defeats DCE per icon, so additions stay curated — extend here +
  // WIDGET_PARITY.md, don't bulk-import. ──
  "AccountBalance" to Icons.Filled.AccountBalance,
  "AccountBalanceWallet" to Icons.Filled.AccountBalanceWallet,
  "Analytics" to Icons.Filled.Analytics,
  "Article" to Icons.Filled.Article,
  "AttachMoney" to Icons.Filled.AttachMoney,
  "BarChart" to Icons.Filled.BarChart,
  "Block" to Icons.Filled.Block,
  "CameraAlt" to Icons.Filled.CameraAlt,
  "ChevronLeft" to Icons.Filled.ChevronLeft,
  "ChevronRight" to Icons.Filled.ChevronRight,
  "ContentCopy" to Icons.Filled.ContentCopy,
  "CreditCard" to Icons.Filled.CreditCard,
  "CurrencyRupee" to Icons.Filled.CurrencyRupee,
  "Description" to Icons.Filled.Description,
  "Download" to Icons.Filled.Download,
  "Fingerprint" to Icons.Filled.Fingerprint,
  "Help" to Icons.Filled.Help,
  "HelpOutline" to Icons.Filled.HelpOutline,
  "History" to Icons.Filled.History,
  "Image" to Icons.Filled.Image,
  "Language" to Icons.Filled.Language,
  "Logout" to Icons.Filled.Logout,
  "OpenInNew" to Icons.Filled.OpenInNew,
  "Payment" to Icons.Filled.Payment,
  "Payments" to Icons.Filled.Payments,
  "Percent" to Icons.Filled.Percent,
  "PieChart" to Icons.Filled.PieChart,
  "PrivacyTip" to Icons.Filled.PrivacyTip,
  "QrCode" to Icons.Filled.QrCode,
  "QrCode2" to Icons.Filled.QrCode2,
  "QrCodeScanner" to Icons.Filled.QrCodeScanner,
  "Receipt" to Icons.Filled.Receipt,
  "ReceiptLong" to Icons.Filled.ReceiptLong,
  "Schedule" to Icons.Filled.Schedule,
  "Security" to Icons.Filled.Security,
  "SupportAgent" to Icons.Filled.SupportAgent,
  "SystemUpdate" to Icons.Filled.SystemUpdate,
  "TrendingDown" to Icons.Filled.TrendingDown,
  "TrendingUp" to Icons.Filled.TrendingUp,
  "Upload" to Icons.Filled.Upload,
  "Verified" to Icons.Filled.Verified,
  "Visibility" to Icons.Filled.Visibility,
  "VisibilityOff" to Icons.Filled.VisibilityOff,
  "Wallet" to Icons.Filled.Wallet,
)

/** Unknown names render a neutral placeholder rather than crashing the screen. */
internal fun iconOrPlaceholder(name: String): ImageVector =
  materialIconByName[name] ?: Icons.Filled.Info
