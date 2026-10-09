package se.pusslet.feature.family

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.feature.family.child.ChildEditorViewModel
import se.pusslet.feature.family.custody.CustodyEditorViewModel
import se.pusslet.feature.family.custody.HandoverViewModel
import se.pusslet.feature.family.invite.InviteViewModel
import se.pusslet.feature.family.join.JoinViewModel
import se.pusslet.feature.family.member.MemberEditorViewModel
import se.pusslet.feature.family.onboarding.OnboardingViewModel
import se.pusslet.feature.family.otherhome.OtherHomeViewModel
import se.pusslet.feature.family.school.SchoolPickerViewModel
import se.pusslet.feature.family.wishlist.WishlistViewModel

val familyModule: Module = module {
    viewModel { OnboardingViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SchoolPickerViewModel(get()) }
    viewModel { JoinViewModel(get(), get(), get(), get()) }
    viewModel { FamilyViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { OtherHomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { ChildEditorViewModel(get(), get(), get()) }
    viewModel { MemberEditorViewModel(get(), get()) }
    viewModel { InviteViewModel(get(), get(), get()) }
    viewModel { CustodyEditorViewModel(get(), get(), get()) }
    viewModel { HandoverViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { WishlistViewModel(get(), get(), get()) }
}
