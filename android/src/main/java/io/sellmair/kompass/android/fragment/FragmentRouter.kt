package io.sellmair.kompass.android.fragment

import io.sellmair.kompass.android.fragment.dsl.FragmentRouterBuilder
import io.sellmair.kompass.android.fragment.dsl.FragmentRouterDsl
import io.sellmair.kompass.android.fragment.internal.FragmentContainerLifecycle
import io.sellmair.kompass.android.fragment.internal.FragmentElementImpl
import io.sellmair.kompass.android.fragment.internal.FragmentRouterConfiguration
import io.sellmair.kompass.android.utils.mainThread
import io.sellmair.kompass.android.utils.requireMainThread
import io.sellmair.kompass.core.Route
import io.sellmair.kompass.core.Router
import io.sellmair.kompass.core.RoutingStack
import io.sellmair.kompass.core.RoutingStackManipulation


class FragmentRouter<T : Route> internal constructor(
    override val fragmentMap: FragmentMap<T>,
    override val fragmentRouteStorage: FragmentRouteStorage<T>,
    private val fragmentTransition: FragmentTransition,
    private val fragmentStackPatcher: FragmentStackPatcher,
    fragmentContainerLifecycleFactory: FragmentContainerLifecycle.Factory,
    initialStack: RoutingStack<T> = RoutingStack.empty()
) :
    Router<T>,
    FragmentRouterConfiguration<T> {

    /**
     * Represents the whole state of this router
     */
    internal sealed class State<T : Route> {

        abstract val stack: RoutingStack<T>

        data class Attached<T : Route>(
            override val stack: RoutingStack<T>,
            val container: FragmentContainer
        ) : State<T>()

        data class Detached<T : Route>(
            override val stack: RoutingStack<T>,
            val pendingStack: RoutingStack<T>
        ) : State<T>()
    }


    internal val fragmentContainerLifecycle: FragmentContainerLifecycle = fragmentContainerLifecycleFactory(this)

    private var state: State<T> = State.Detached(stack = RoutingStack.empty(), pendingStack = initialStack)
        set(newState) {
            requireMainThread()
            val oldState = field
            field = newState
            onStateChanged(oldState = oldState, newState = newState)
        }
        get() {
            requireMainThread()
            return field
        }


    override fun execute(instruction: RoutingStackManipulation<T>) = mainThread {
        state = state.nextState(instruction)
    }

    internal fun attachContainer(container: FragmentContainer) {
        requireMainThread()
        val state = this.state
        check(state is State.Detached<T>)
        this.state = State.Attached(state.pendingStack, container)
    }

    internal fun detachContainer() {
        requireMainThread()
        val state = this.state as? State.Attached<T> ?: return
        this.state = State.Detached(
            stack = state.stack,
            pendingStack = state.stack
        )
    }

    private fun State<T>.nextState(instruction: RoutingStackManipulation<T>): State<T> = when (this) {
        is State.Attached -> copy(stack = stack.instruction())
        is State.Detached -> copy(pendingStack = pendingStack.instruction())
    }


    private fun onStateChanged(oldState: State<T>, newState: State<T>) {
        if (newState is State.Attached<T>) {
            apply(oldState, newState)
        }
    }

    private fun apply(oldState: State<T>, newState: State.Attached<T>) {
        fragmentStackPatcher(fragmentTransition, newState.container, oldState.stack, prepareFragmentStack(newState))
    }

    private fun prepareFragmentStack(state: State.Attached<T>): FragmentRoutingStack<T> {
        val factory = FragmentElementImpl.Factory(this, state.container)
        return FragmentRoutingStack(state.stack.elements, factory)
    }


    companion object Factory {
        @FragmentRouterDsl
        inline operator fun <reified T : Route> invoke(init: FragmentRouterBuilder<T>.() -> Unit): FragmentRouter<T> {
            return FragmentRouterBuilder(T::class).also(init).build()
        }
    }
}


